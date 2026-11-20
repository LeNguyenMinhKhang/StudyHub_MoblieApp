// Contract tests for the group call Edge handlers with a fake database and provider.
import test from 'node:test';
import assert from 'node:assert/strict';
import { handleCall, handleWebhook, handleWorker, drainJobs, ProviderError, TOKEN_TTL_SECONDS }
  from '../../../functions/_shared/group_calls.ts';

const ACTOR = '00000000-0000-4000-8000-000000000001';
const GROUP = '00000000-0000-4000-8000-0000000000a1';
const CALL = '00000000-0000-4000-8000-0000000000c1';
const ADMISSION = '00000000-0000-4000-8000-0000000000d1';
const IDENTITY = '00000000-0000-4000-8000-0000000000e1';
const ROOM = 'sh-00000000-0000-4000-8000-0000000000f1';
const SECRET = 'w'.repeat(40);
const NOW = Date.UTC(2026, 10, 18, 9, 0, 0);
const projection = { id: CALL, group_id: GROUP, status: 'active', participant_count: 1, capacity: 6 };

function fakes({ rpc = {}, provider = {}, user = ACTOR } = {}) {
  const calls = [];
  const providerCalls = [];
  const responses = {
    service_admit_group_call: { data: { call: { ...projection, status: 'starting' },
      admission: { id: ADMISSION, identity: IDENTITY, generation: 3, room_name: ROOM, needs_room: true, display_name: 'Lan 📚' } } },
    service_activate_group_call: { data: { id: CALL, status: 'active' } },
    service_confirm_group_call_admission: { data: { call: projection } },
    service_release_group_call_admission: { data: null },
    service_end_group_call: { data: { call: { ...projection, status: 'ending' } } },
    service_leave_group_call: { data: { call_id: CALL, status: 'left' } },
    service_claim_group_call_jobs: { data: [] },
    service_complete_group_call_job: { data: { status: 'done' } },
    service_fail_group_call_job: { data: { status: 'pending' } },
    service_record_group_call_event: { data: { duplicate: false, applied: 'connected' } },
    service_reconcile_group_calls: { data: [] },
    service_record_group_call_presence: { data: { status: 'active' } },
    ...rpc,
  };
  const fakeProvider = {
    url: 'wss://example.livekit.cloud',
    async createRoom(name) { providerCalls.push(['createRoom', name]); return { sid: 'RM_1' }; },
    async token(room, identity, ttl, name) { providerCalls.push(['token', room, identity, ttl, name]); return 'signed.jwt.value'; },
    async removeParticipant(room, identity, cutoff) { providerCalls.push(['remove', room, identity, cutoff]); },
    async deleteRoom(room) { providerCalls.push(['delete', room]); },
    async listParticipants(room) { providerCalls.push(['list', room]); return [IDENTITY, 'not-a-uuid']; },
    async verifyWebhook(body, auth) {
      if (auth !== 'signed') throw new ProviderError('unauthorized', 'bad');
      return JSON.parse(body);
    },
    ...provider,
  };
  const deps = {
    verifyUser: async (jwt) => (jwt === 'good' ? user : null),
    rpc: async (name, args) => {
      calls.push([name, args]);
      const r = responses[name];
      const value = typeof r === 'function' ? r(args, calls) : r;
      return { data: value?.data ?? null, error: value?.error ?? null };
    },
    provider: () => fakeProvider,
    now: () => NOW,
    workerSecret: SECRET,
  };
  return { deps, calls, providerCalls };
}

const post = (body, auth = 'Bearer good') => new Request('https://fn.local/group-call', {
  method: 'POST', headers: auth ? { authorization: auth } : {}, body: typeof body === 'string' ? body : JSON.stringify(body),
});
const start = (extra = {}) => post({ action: 'start_or_join', group_id: GROUP, request_id: crypto.randomUUID(),
  client_session_id: crypto.randomUUID(), mode: 'video', ...extra });

test('start creates the room after commit, signs a 5-minute token and re-checks before returning it', async () => {
  const { deps, calls, providerCalls } = fakes();
  // Forged identity/room fields are ignored: the actor comes from the verified JWT.
  const response = await handleCall(start({ user_id: 'someone-else', room: 'other-room' }), deps);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(Object.keys(body.connection).sort(), ['expires_at', 'identity', 'token', 'url']);
  assert.equal(body.connection.token, 'signed.jwt.value');
  assert.equal(body.connection.identity, IDENTITY);
  assert.equal(body.connection.expires_at, new Date(NOW + TOKEN_TTL_SECONDS * 1000).toISOString());
  assert.ok(!JSON.stringify(body).includes(ROOM), 'room name is not exposed');
  assert.deepEqual(providerCalls, [['createRoom', ROOM], ['token', ROOM, IDENTITY, 300, 'Lan 📚']]);
  assert.deepEqual(calls.map(([n]) => n), ['service_admit_group_call', 'service_activate_group_call',
    'service_confirm_group_call_admission']);
  assert.equal(calls[0][1].p_actor, ACTOR);
  assert.equal(calls[1][1].p_room_sid, 'RM_1');
});

test('joining an active call does not create a room', async () => {
  const admitted = { data: { call: projection, admission: { id: ADMISSION, identity: IDENTITY, generation: 1, room_name: ROOM, needs_room: false } } };
  const { deps, providerCalls } = fakes({ rpc: { service_admit_group_call: admitted } });
  assert.equal((await handleCall(start(), deps)).status, 200);
  assert.deepEqual(providerCalls.map(([n]) => n), ['token']);
});

test('missing or forged sessions and malformed input never reach the database', async () => {
  for (const [request, status, code] of [
    [post({ action: 'start_or_join' }, null), 401, 'G0002'],
    [post({ action: 'start_or_join' }, 'Bearer forged'), 401, 'G0002'],
    [start({ group_id: 'x' }), 400, 'G0001'],
    [start({ mode: 'screen' }), 400, 'G0001'],
    [post('{not json'), 400, 'G0001'],
    [post({ action: 'record' }), 400, 'G0001'],
    [new Request('https://fn.local', { method: 'GET' }), 405, 'method_not_allowed'],
  ]) {
    const { deps, calls } = fakes();
    const response = await handleCall(request, deps);
    assert.equal(response.status, status);
    assert.equal((await response.json()).error.code, code);
    assert.equal(calls.length, 0);
  }
});

test('provider failures release the admission and never return a token', async () => {
  for (const [kind, status, code] of [['quota', 429, 'provider_quota'], ['unavailable', 503, 'provider_unavailable']]) {
    const { deps, calls, providerCalls } = fakes({ provider: { async createRoom() { throw new ProviderError(kind, 'x'); } } });
    const response = await handleCall(start(), deps);
    assert.equal(response.status, status);
    assert.equal((await response.json()).error.code, code);
    assert.deepEqual(calls.map(([n]) => n), ['service_admit_group_call', 'service_release_group_call_admission']);
    assert.ok(!providerCalls.some(([n]) => n === 'token'));
  }
  const unconfigured = fakes();
  unconfigured.deps.provider = () => { throw new ProviderError('unavailable', 'not configured'); };
  assert.equal((await handleCall(start(), unconfigured.deps)).status, 503);
  assert.equal(unconfigured.calls.length, 0);
});

test('a call that started ending meanwhile, or a removal before confirm, discards the token', async () => {
  const ending = fakes({ rpc: { service_activate_group_call: { data: { id: CALL, status: 'ending' } } } });
  const r1 = await handleCall(start(), ending.deps);
  assert.equal(r1.status, 409);
  assert.equal((await r1.json()).error.code, 'C0004');
  assert.ok(ending.calls.some(([n]) => n === 'service_release_group_call_admission'));

  const removed = fakes({ rpc: { service_confirm_group_call_admission: { error: { code: 'G0004', message: 'group unavailable' } } } });
  const r2 = await handleCall(start(), removed.deps);
  assert.equal(r2.status, 410);
  const body = await r2.text();
  assert.ok(!body.includes('signed.jwt.value'));
  assert.deepEqual(JSON.parse(body), { error: { code: 'G0004' } });

  for (const [code, status] of [['C0001', 409], ['C0002', 409], ['G0003', 403], ['XX000', 500]]) {
    const failed = fakes({ rpc: { service_admit_group_call: { error: { code, message: 'secret detail from SQL' } } } });
    const response = await handleCall(start(), failed.deps);
    assert.equal(response.status, status);
    const text = await response.text();
    assert.ok(!text.includes('secret detail'));
    assert.equal(JSON.parse(text).error.code, code === 'XX000' ? 'server_error' : code);
  }
});

test('end-for-all drains the end_room job: revoke every identity, delete room, then confirm', async () => {
  let claims = 0;
  const job = { id: 7, kind: 'end_room', call_id: CALL, room_name: ROOM, identities: [IDENTITY, ADMISSION], attempts: 1 };
  const { deps, calls, providerCalls } = fakes({ rpc: {
    service_claim_group_call_jobs: () => ({ data: claims++ === 0 ? [job] : [] }),
    service_end_group_call: (_, all) => ({ data: { call: { ...projection,
      status: all.some(([n]) => n === 'service_complete_group_call_job') ? 'ended' : 'ending' } } }),
  } });
  const response = await handleCall(post({ action: 'end', call_id: CALL }), deps);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).call.status, 'ended');
  const cutoff = Math.floor(NOW / 1000) + 1;
  assert.deepEqual(providerCalls, [['remove', ROOM, IDENTITY, cutoff], ['remove', ROOM, ADMISSION, cutoff], ['delete', ROOM]]);
  const complete = calls.find(([n]) => n === 'service_complete_group_call_job');
  assert.deepEqual(complete[1], { p_job_id: 7, p_identities: [IDENTITY, ADMISSION] });
});

test('provider failure during jobs is retried later and never confirmed', async () => {
  const job = { id: 9, kind: 'revoke', call_id: CALL, room_name: ROOM, identities: [IDENTITY], attempts: 1 };
  const { deps, calls } = fakes({
    rpc: { service_claim_group_call_jobs: { data: [job] } },
    provider: { async removeParticipant() { throw new ProviderError('unavailable', 'down'); } },
  });
  const stats = await drainJobs(deps, { budgetMs: 1000 });
  assert.deepEqual(stats, { done: 0, failed: 1, pending: 0 });
  assert.deepEqual(calls.filter(([n]) => n !== 'service_claim_group_call_jobs'),
    [['service_fail_group_call_job', { p_job_id: 9, p_error: 'provider_unavailable' }]]);
});

test('webhooks require a provider signature and map events without trusting other fields', async () => {
  const event = { id: 'EV_1', event: 'participant_joined', roomName: ROOM, roomSid: 'RM_1', identity: IDENTITY, createdAtSeconds: 1763456400 };
  const hook = (body, auth) => new Request('https://fn.local/hook', { method: 'POST', headers: auth ? { authorization: auth } : {}, body });
  const bad = fakes();
  assert.equal((await handleWebhook(hook(JSON.stringify(event), 'forged'), bad.deps)).status, 401);
  assert.equal((await handleWebhook(hook(JSON.stringify(event)), bad.deps)).status, 401);
  assert.equal(bad.calls.length, 0);

  const ok = fakes();
  const response = await handleWebhook(hook(JSON.stringify({ ...event, identity: 'agent-not-uuid' }), 'signed'), ok.deps);
  assert.equal(response.status, 200);
  assert.deepEqual(ok.calls[0], ['service_record_group_call_event', { p_event_id: 'EV_1', p_event: 'participant_joined',
    p_room_name: ROOM, p_room_sid: 'RM_1', p_identity: null, p_created_at: '2025-11-18T09:00:00.000Z' }]);
  assert.equal(ok.calls.length, 1); // 'connected' needs no provider work

  let claims = 0;
  const revoke = fakes({ rpc: {
    service_record_group_call_event: { data: { duplicate: false, applied: 'revoke' } },
    service_claim_group_call_jobs: () => ({ data: claims++ === 0
      ? [{ id: 3, kind: 'revoke', call_id: CALL, room_name: ROOM, identities: [IDENTITY], attempts: 1 }] : [] }),
  } });
  assert.equal((await handleWebhook(hook(JSON.stringify(event), 'signed'), revoke.deps)).status, 200);
  assert.deepEqual(revoke.providerCalls.map(([n]) => n), ['remove']); // late join revoked immediately

  const dbDown = fakes({ rpc: { service_record_group_call_event: { error: { code: 'XX000' } } } });
  assert.equal((await handleWebhook(hook(JSON.stringify(event), 'signed'), dbDown.deps)).status, 500); // provider retries
});

test('worker requires the scheduler secret, reconciles presence and drains jobs', async () => {
  const worker = (auth) => new Request('https://fn.local/worker', { method: 'POST', headers: auth ? { authorization: auth } : {} });
  for (const auth of [null, 'Bearer wrong', `Bearer ${SECRET}x`]) {
    const { deps, calls } = fakes();
    assert.equal((await handleWorker(worker(auth), deps)).status, 401);
    assert.equal(calls.length, 0);
  }
  const short = fakes();
  short.deps.workerSecret = 'short';
  assert.equal((await handleWorker(worker('Bearer short'), short.deps)).status, 401);

  const other = '00000000-0000-4000-8000-0000000000c2';
  const { deps, calls } = fakes({
    rpc: { service_reconcile_group_calls: { data: [{ id: CALL, room_name: ROOM }, { id: other, room_name: 'sh-gone' }] } },
    provider: { async listParticipants(room) { if (room === 'sh-gone') return null; return [IDENTITY, 'not-a-uuid']; } },
  });
  const response = await handleWorker(worker(`Bearer ${SECRET}`), deps);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).reconciled, 2);
  const presence = calls.filter(([n]) => n === 'service_record_group_call_presence').map(([, a]) => a);
  assert.deepEqual(presence, [{ p_call_id: CALL, p_identities: [IDENTITY] }, { p_call_id: other, p_identities: [] }]);
  assert.ok(calls.some(([n]) => n === 'service_claim_group_call_jobs'));
});
