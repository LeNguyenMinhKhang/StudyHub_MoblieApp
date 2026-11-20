// Opt-in LiveKit Cloud probe (task 6.1). Requires LIVEKIT_URL, LIVEKIT_API_KEY and
// LIVEKIT_API_SECRET in the process environment; never commit or log them. Each run uses
// fresh UUID rooms and deletes them afterwards. Results are printed as PROBE lines.
import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { AccessToken, RoomServiceClient, TrackSource as GrantSource } from 'livekit-server-sdk';
import {
  Room, RoomEvent, DisconnectReason, AudioSource, VideoSource, VideoFrame, VideoBufferType,
  LocalAudioTrack, LocalVideoTrack, TrackPublishOptions, TrackSource, dispose,
} from '@livekit/rtc-node';

const { LIVEKIT_URL: url, LIVEKIT_API_KEY: key, LIVEKIT_API_SECRET: secret } = process.env;
const configured = Boolean(url && key && secret);
const rooms = configured ? new RoomServiceClient(url.replace(/^ws/, 'http'), key, secret) : null;
const created = new Set();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (name, value) => console.log(`PROBE ${name}: ${typeof value === 'string' ? value : JSON.stringify(value)}`);

/** Same grant shape as the planned start/join endpoint: one room, mic/camera only, 5 min TTL. */
async function token(room, identity) {
  const at = new AccessToken(key, secret, { identity, ttl: '5m' });
  at.addGrant({ room, roomJoin: true, canSubscribe: true, canPublish: true, canPublishData: false,
    canPublishSources: [GrantSource.MICROPHONE, GrantSource.CAMERA] });
  return at.toJwt();
}

async function join(room, jwt) {
  const r = new Room();
  await r.connect(url, jwt, { autoSubscribe: false, dynacast: false });
  return r;
}

async function joinFails(room, jwt) {
  const r = new Room();
  try {
    await Promise.race([r.connect(url, jwt, { autoSubscribe: false }), sleep(15000).then(() => { throw new Error('timeout'); })]);
    await r.disconnect();
    return { rejected: false };
  } catch (error) {
    await r.disconnect().catch(() => {});
    return { rejected: true, error: String(error?.message ?? error).slice(0, 160) };
  }
}

function newRoom() { const name = `probe-${randomUUID()}`; created.add(name); return name; }

after(async () => {
  for (const name of created) await rooms?.deleteRoom(name).catch(() => {});
  await dispose();
});

test('grants allow microphone and refuse screen sharing', { skip: !configured }, async () => {
  const room = newRoom();
  const a = await join(room, await token(room, 'a'));
  try {
    const mic = LocalAudioTrack.createAudioTrack('mic', new AudioSource(48000, 1));
    await a.localParticipant.publishTrack(mic, new TrackPublishOptions({ source: TrackSource.SOURCE_MICROPHONE }));
    const screenSource = new VideoSource(64, 64);
    screenSource.captureFrame(new VideoFrame(new Uint8Array(64 * 64 * 4), 64, 64, VideoBufferType.RGBA));
    const screen = LocalVideoTrack.createVideoTrack('screen', screenSource);
    let screenResult;
    try {
      await Promise.race([a.localParticipant.publishTrack(screen, new TrackPublishOptions({ source: TrackSource.SOURCE_SCREENSHARE })),
        sleep(10000).then(() => { throw new Error('publish timeout'); })]);
      screenResult = 'published';
    } catch (error) { screenResult = `refused: ${String(error?.message ?? error).slice(0, 120)}`; }
    await sleep(1500);
    const [participant] = await rooms.listParticipants(room);
    const sources = participant.tracks.map((t) => t.source);
    log('screen_share_publish', screenResult);
    log('server_track_sources', sources);
    assert.ok(sources.includes(GrantSource.MICROPHONE), 'microphone track is visible to the server');
    assert.ok(!sources.includes(GrantSource.SCREEN_SHARE), 'screen share must never reach the room');
  } finally { await a.disconnect(); }
});

test('RemoveParticipant with cutoff disconnects and blocks the cached token', { skip: !configured }, async () => {
  const room = newRoom();
  const cached = await token(room, 'b');
  const b = await join(room, cached);
  const disconnected = new Promise((resolve) => b.on(RoomEvent.Disconnected, (reason) => resolve(reason)));
  await sleep(1100); // the cutoff has second resolution; keep it strictly after issuance
  const cutoff = BigInt(Math.floor(Date.now() / 1000));
  const started = Date.now();
  await rooms.removeParticipant(room, 'b', { revokeTokenTs: cutoff });
  const reason = await Promise.race([disconnected, sleep(15000).then(() => 'timeout')]);
  const latency = Date.now() - started;
  log('remove_disconnect', { reason: DisconnectReason[reason] ?? reason, latency_ms: latency });
  assert.equal(reason, DisconnectReason.PARTICIPANT_REMOVED);
  assert.ok(latency < 10000, 'within the 10 s revocation target');

  const reuse = await joinFails(room, cached);
  log('cached_token_after_cutoff', reuse);
  assert.equal(reuse.rejected, true, 'a token issued before the cutoff must not reconnect');

  await sleep(1100);
  const late = await joinFails(room, await token(room, 'b'));
  // Expected: accepted. The cutoff only covers earlier tokens, so the DB admission check must
  // stop new issuance after removal; the provider cannot do it alone.
  log('token_issued_after_cutoff', late);
});

test('revocation of an identity that never joined, and ended-room recreation', { skip: !configured }, async () => {
  const room = newRoom();
  const owner = await join(room, await token(room, 'owner'));
  const idle = await token(room, 'idle'); // issued (cached on a device) but never used
  let absent;
  try {
    await rooms.removeParticipant(room, 'idle', { revokeTokenTs: BigInt(Math.floor(Date.now() / 1000) + 1) });
    absent = 'accepted';
  } catch (error) { absent = `error: ${String(error?.message ?? error).slice(0, 120)}`; }
  log('remove_absent_identity', absent);

  const ownerCached = await token(room, 'owner2');
  await sleep(1100);
  // Planned end-room sequence: revoke every connected identity, then delete the room.
  await rooms.removeParticipant(room, 'owner', { revokeTokenTs: BigInt(Math.floor(Date.now() / 1000)) });
  await owner.disconnect();
  await rooms.deleteRoom(room);
  await sleep(2000);

  const idleJoin = await joinFails(room, idle);
  log('never_joined_token_after_end', idleJoin);
  const recreated = (await rooms.listRooms([room])).length > 0;
  log('room_recreated_by_cached_token', recreated);
  const unrevoked = await joinFails(room, ownerCached);
  log('unrevoked_identity_after_end', unrevoked);
});
