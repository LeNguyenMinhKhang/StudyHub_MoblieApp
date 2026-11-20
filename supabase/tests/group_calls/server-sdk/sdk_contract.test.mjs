import assert from 'node:assert/strict';
import test from 'node:test';
import { AccessToken, RoomServiceClient, TokenVerifier, TrackSource } from 'livekit-server-sdk';

// Public fixtures only. No process credentials or provider connections are used.
const apiKey = 'studyhub-offline-test';
const apiSecret = 'studyhub-offline-fixture-not-a-provider-secret';
const room = 'room-opaque-fixture';
const identity = 'admission-opaque-fixture';
const verifier = new TokenVerifier(apiKey, apiSecret);

test('five-minute participant JWT contains only the intended media grants', async () => {
  const token = new AccessToken(apiKey, apiSecret, { identity, ttl: 300 });
  token.addGrant({ room, roomJoin: true, canSubscribe: true, canPublish: true,
    canPublishSources: [TrackSource.MICROPHONE, TrackSource.CAMERA],
    canPublishData: false, canUpdateOwnMetadata: false });
  const jwt = await token.toJwt();
  const claims = await verifier.verify(jwt);
  assert.equal(claims.sub, identity);
  assert.equal(claims.iss, apiKey);
  assert.equal(claims.exp - claims.nbf, 300);
  // Inspect the signed wire payload: the SDK converts TrackSource enums to strings.
  const payload = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url'));
  assert.deepEqual(payload.video, {
    room, roomJoin: true, canSubscribe: true, canPublish: true,
    canPublishSources: ['microphone', 'camera'],
    canPublishData: false, canUpdateOwnMetadata: false,
  });
  assert.equal(payload.metadata, undefined);
  assert.equal(payload.attributes, undefined);
  await assert.rejects(new TokenVerifier(apiKey, 'incorrect-fixture-secret').verify(jwt));
});

test('RemoveParticipant serializes explicit Unix-second cutoff with room-admin authorization', async (t) => {
  const cutoff = BigInt(Math.floor(Date.now() / 1000));
  let requests = 0;
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    requests++;
    assert.equal(url.toString(), 'https://offline.invalid/twirp/livekit.RoomService/RemoveParticipant');
    assert.equal(init.method, 'POST');
    assert.deepEqual(JSON.parse(init.body), { room, identity, revokeTokenTs: cutoff.toString() });
    const authorization = init.headers.Authorization ?? init.headers.authorization;
    const claims = await verifier.verify(authorization.replace(/^Bearer /, ''));
    assert.deepEqual(claims.video, { roomAdmin: true, room });
    return Response.json({});
  });
  const client = new RoomServiceClient('https://offline.invalid', apiKey, apiSecret, { failover: false });
  await client.removeParticipant(room, identity, { revokeTokenTs: cutoff });
  assert.equal(requests, 1);
});

test('provider refusal stays a failure rather than a successful revocation', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json(
    { code: 'unavailable', msg: 'fixture provider unavailable' }, { status: 503 }));
  const client = new RoomServiceClient('https://offline.invalid', apiKey, apiSecret, { failover: false });
  await assert.rejects(client.removeParticipant(room, identity, {
    revokeTokenTs: BigInt(Math.floor(Date.now() / 1000)),
  }), error => error.status === 503 && error.code === 'unavailable');
});
