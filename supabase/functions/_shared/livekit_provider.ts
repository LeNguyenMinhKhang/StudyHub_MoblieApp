// LiveKit adapter for the call handlers. The SDK module is injected so the same adapter runs
// on Deno (npm:livekit-server-sdk@2.19.3) and in the Node cloud probe (pinned in package.json).
// Credentials come from server-side environment only and are never returned or logged.

import { ProviderError } from "./group_calls.ts";
import type { CallProvider, ProviderWebhookEvent } from "./group_calls.ts";

// deno-lint-ignore no-explicit-any
type Sdk = any;

export interface LiveKitConfig {
  url: string;
  apiKey: string;
  apiSecret: string;
}

export const MAX_PARTICIPANTS = 6;

function classify(error: unknown): ProviderError {
  if (error instanceof ProviderError) return error;
  const status = Number((error as { status?: number })?.status);
  const code = String((error as { code?: string })?.code ?? "");
  if (status === 404 || code === "not_found") return new ProviderError("not_found", "not found");
  if (status === 429 || code === "resource_exhausted") return new ProviderError("quota", "quota exceeded");
  return new ProviderError("unavailable", "provider unavailable");
}

export function createLiveKitProvider(sdk: Sdk, config: LiveKitConfig): CallProvider {
  if (!config.url || !config.apiKey || !config.apiSecret) throw new ProviderError("unavailable", "provider not configured");
  const httpUrl = config.url.replace(/^ws/, "http");
  const rooms = new sdk.RoomServiceClient(httpUrl, config.apiKey, config.apiSecret);
  const receiver = new sdk.WebhookReceiver(config.apiKey, config.apiSecret);
  const source = sdk.TrackSource;

  return {
    url: config.url,

    async createRoom(name) {
      try {
        // Idempotent for an existing name; maxParticipants is a second guard behind DB capacity.
        const room = await rooms.createRoom({ name, maxParticipants: MAX_PARTICIPANTS, emptyTimeout: 60, departureTimeout: 20 });
        return { sid: String(room.sid ?? "") };
      } catch (error) {
        throw classify(error);
      }
    },

    async token(room, identity, ttlSeconds, name) {
      const token = new sdk.AccessToken(config.apiKey, config.apiSecret, { identity, name: Array.from(name).slice(0, 100).join(""), ttl: ttlSeconds });
      // One room, subscribe + microphone/camera only. No admin, create, record, data or screen share.
      token.addGrant({
        room, roomJoin: true, canSubscribe: true, canPublish: true, canPublishData: false,
        canPublishSources: [source.MICROPHONE, source.CAMERA],
        roomAdmin: false, roomCreate: false, roomList: false, roomRecord: false, hidden: false,
      });
      return await token.toJwt();
    },

    async removeParticipant(room, identity, cutoffSeconds) {
      try {
        // Verified on Cloud (2026-11-17): accepted for absent identities and missing rooms, and
        // tokens issued before the cutoff are then rejected with "invalid token: revoked".
        await rooms.removeParticipant(room, identity, { revokeTokenTs: BigInt(cutoffSeconds) });
      } catch (error) {
        throw classify(error);
      }
    },

    async deleteRoom(room) {
      try {
        await rooms.deleteRoom(room);
      } catch (error) {
        const failure = classify(error);
        if (failure.kind !== "not_found") throw failure; // already gone
      }
    },

    async listParticipants(room) {
      try {
        const participants = await rooms.listParticipants(room);
        return participants.map((p: { identity: string }) => p.identity);
      } catch (error) {
        const failure = classify(error);
        if (failure.kind === "not_found") return null;
        throw failure;
      }
    },

    async verifyWebhook(body, authorization): Promise<ProviderWebhookEvent> {
      if (!authorization) throw new ProviderError("unauthorized", "missing signature");
      let event;
      try {
        event = await receiver.receive(body, authorization);
      } catch {
        throw new ProviderError("unauthorized", "invalid signature");
      }
      const created = event.createdAt;
      return {
        id: String(event.id ?? ""),
        event: String(event.event ?? ""),
        roomName: event.room?.name ?? null,
        roomSid: event.room?.sid || null,
        identity: event.participant?.identity ?? null,
        createdAtSeconds: created === undefined || created === null ? null : Number(created),
      };
    },
  };
}
