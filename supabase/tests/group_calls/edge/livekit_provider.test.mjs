// Offline checks of the LiveKit adapter with the pinned server SDK (no network).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import * as sdk from 'livekit-server-sdk';
import { createLiveKitProvider } from '../../../functions/_shared/livekit_provider.ts';
import { ProviderError } from '../../../functions/_shared/group_calls.ts';

const config = { url: 'wss://example.livekit.cloud', apiKey: 'APItestkey', apiSecret: 's'.repeat(43) };
const decode = (jwt) => JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8'));

test('tokens bind one identity and room with microphone/camera only and a 5-minute TTL', async () => {
  const provider = createLiveKitProvider(sdk, config);
  const claims = decode(await provider.token('sh-room', 'identity-1', 300, 'Lan 📚'));
  assert.equal(claims.sub, 'identity-1');
  assert.equal(claims.name, 'Lan 📚');
  assert.equal(claims.exp - claims.nbf, 300);
  const video = claims.video;
  assert.equal(video.room, 'sh-room');
  assert.equal(video.roomJoin, true);
  assert.equal(video.canPublishData, false);
  assert.deepEqual(video.canPublishSources, ['microphone', 'camera']);
  for (const admin of ['roomAdmin', 'roomCreate', 'roomList', 'roomRecord', 'ingressAdmin', 'agent']) {
    assert.ok(!video[admin], `${admin} must not be granted`);
  }
  assert.ok(!JSON.stringify(claims).includes(config.apiSecret));
});

test('webhooks are accepted only with a valid signature over the exact body', async () => {
  const provider = createLiveKitProvider(sdk, config);
  const body = JSON.stringify({ id: 'EV_1', event: 'participant_joined', createdAt: '1763456400',
    room: { name: 'sh-room', sid: 'RM_1' }, participant: { identity: 'identity-1' } });
  const signer = new sdk.AccessToken(config.apiKey, config.apiSecret);
  signer.sha256 = createHash('sha256').update(body).digest('base64');
  const auth = await signer.toJwt();
  const event = await provider.verifyWebhook(body, auth);
  assert.deepEqual(event, { id: 'EV_1', event: 'participant_joined', roomName: 'sh-room', roomSid: 'RM_1',
    identity: 'identity-1', createdAtSeconds: 1763456400 });
  for (const [b, a] of [[body.replace('identity-1', 'identity-2'), auth], [body, null], [body, 'garbage']]) {
    await assert.rejects(provider.verifyWebhook(b, a), (e) => e instanceof ProviderError && e.kind === 'unauthorized');
  }
  const other = createLiveKitProvider(sdk, { ...config, apiSecret: 'x'.repeat(43) });
  await assert.rejects(other.verifyWebhook(body, auth), (e) => e.kind === 'unauthorized');
});

test('provider errors are classified; missing rooms are not failures for delete/list', async () => {
  const failing = (status, code) => ({ ...sdk, RoomServiceClient: class {
    async createRoom() { throw Object.assign(new Error('x'), { status, code }); }
    async deleteRoom() { throw Object.assign(new Error('x'), { status, code }); }
    async listParticipants() { throw Object.assign(new Error('x'), { status, code }); }
    async removeParticipant() { throw Object.assign(new Error('x'), { status, code }); }
  } });
  const quota = createLiveKitProvider(failing(429, 'resource_exhausted'), config);
  await assert.rejects(quota.createRoom('r'), (e) => e.kind === 'quota');
  const down = createLiveKitProvider(failing(503, 'unavailable'), config);
  await assert.rejects(down.removeParticipant('r', 'i', 1), (e) => e.kind === 'unavailable');
  await assert.rejects(down.deleteRoom('r'), (e) => e.kind === 'unavailable');
  const missing = createLiveKitProvider(failing(404, 'not_found'), config);
  await missing.deleteRoom('r');
  assert.equal(await missing.listParticipants('r'), null);
  assert.throws(() => createLiveKitProvider(sdk, { ...config, apiSecret: '' }), (e) => e.kind === 'unavailable');
});
