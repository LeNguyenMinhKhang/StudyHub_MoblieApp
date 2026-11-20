// Opt-in: runs the production adapter + drainJobs (supabase/functions/_shared) against the
// LiveKit Cloud test project with a fake database. Requires LIVEKIT_* in the environment.
import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import * as sdk from 'livekit-server-sdk';
import { Room, RoomEvent, DisconnectReason, dispose } from '@livekit/rtc-node';
import { createLiveKitProvider } from '../../../functions/_shared/livekit_provider.ts';
import { drainJobs } from '../../../functions/_shared/group_calls.ts';

const { LIVEKIT_URL: url, LIVEKIT_API_KEY: apiKey, LIVEKIT_API_SECRET: apiSecret } = process.env;
const configured = Boolean(url && apiKey && apiSecret);
const provider = configured ? createLiveKitProvider(sdk, { url, apiKey, apiSecret }) : null;
const created = new Set();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (name, value) => console.log(`PROBE ${name}: ${JSON.stringify(value)}`);
after(async () => { for (const r of created) await provider?.deleteRoom(r).catch(() => {}); await dispose(); });

async function connect(token) { const room = new Room(); await room.connect(url, token, { autoSubscribe: false }); return room; }
async function joinFails(token) {
  const room = new Room();
  try { await room.connect(url, token, { autoSubscribe: false }); await room.disconnect(); return false; }
  catch { await room.disconnect().catch(() => {}); return true; }
}
/** Fake DB handing out one job, recording completion like the SQL functions would. */
function fakeDb(job) {
  const log = [];
  let claimed = false;
  return { log, deps: {
    verifyUser: async () => null, now: () => Date.now(), provider: () => provider,
    rpc: async (name, args) => {
      log.push([name, args]);
      if (name === 'service_claim_group_call_jobs') { const data = claimed ? [] : [job]; claimed = true; return { data, error: null }; }
      if (name === 'service_complete_group_call_job') return { data: { status: 'done' }, error: null };
      return { data: { status: 'pending' }, error: null };
    },
  } };
}

test('end_room job revokes connected and never-joined identities; ended room is not recreated', { skip: !configured }, async () => {
  const room = `sh-${randomUUID()}`; created.add(room);
  assert.ok((await provider.createRoom(room)).sid.startsWith('RM_'));
  const [a, b] = [randomUUID(), randomUUID()];
  const tokenA = await provider.token(room, a, 300, 'A');
  const tokenB = await provider.token(room, b, 300, 'B'); // issued, never used (cached on a device)
  const connected = await connect(tokenA);
  const gone = new Promise((resolve) => connected.on(RoomEvent.Disconnected, resolve));
  assert.deepEqual(await provider.listParticipants(room), [a]);
  await sleep(1100);
  const { deps, log: calls } = fakeDb({ id: 1, kind: 'end_room', call_id: 'c', room_name: room, identities: [a, b], attempts: 1 });
  const started = Date.now();
  const stats = await drainJobs(deps, { budgetMs: 15000 });
  const reason = await Promise.race([gone, sleep(10000).then(() => 'timeout')]);
  log('end_room', { stats, reason: DisconnectReason[reason] ?? reason, disconnect_ms: Date.now() - started });
  assert.deepEqual(stats, { done: 1, failed: 0, pending: 0 });
  assert.notEqual(reason, 'timeout');
  assert.deepEqual(calls.find(([n]) => n === 'service_complete_group_call_job')[1], { p_job_id: 1, p_identities: [a, b] });
  await sleep(1500);
  assert.equal(await provider.listParticipants(room), null, 'room deleted');
  assert.equal(await joinFails(tokenA), true, 'connected identity cannot reconnect');
  assert.equal(await joinFails(tokenB), true, 'never-joined cached token cannot recreate the room');
  assert.equal(await provider.listParticipants(room), null, 'room not recreated');
});

test('revoke job disconnects within the 10 s target and blocks the cached token', { skip: !configured }, async () => {
  const room = `sh-${randomUUID()}`; created.add(room);
  await provider.createRoom(room);
  const identity = randomUUID();
  const token = await provider.token(room, identity, 300, 'C');
  const client = await connect(token);
  const gone = new Promise((resolve) => client.on(RoomEvent.Disconnected, resolve));
  await sleep(1100);
  const started = Date.now();
  const { deps } = fakeDb({ id: 2, kind: 'revoke', call_id: 'c', room_name: room, identities: [identity], attempts: 1 });
  await drainJobs(deps, { budgetMs: 15000 });
  const reason = await Promise.race([gone, sleep(10000).then(() => 'timeout')]);
  const latency = Date.now() - started;
  log('revoke_job', { reason: DisconnectReason[reason] ?? reason, latency_ms: latency });
  assert.equal(reason, DisconnectReason.PARTICIPANT_REMOVED);
  assert.ok(latency < 10000);
  assert.equal(await joinFails(token), true);
});

test('revocation is accepted for a room that does not exist yet; delete of a missing room is not an error', { skip: !configured }, async () => {
  const room = `sh-${randomUUID()}`; created.add(room);
  const identity = randomUUID();
  const token = await provider.token(room, identity, 300, 'C');
  await sleep(1100);
  await provider.removeParticipant(room, identity, Math.floor(Date.now() / 1000) + 1);
  assert.equal(await joinFails(token), true);
  await provider.deleteRoom(`sh-${randomUUID()}`);
  assert.equal(await provider.listParticipants(`sh-${randomUUID()}`), null);
});
