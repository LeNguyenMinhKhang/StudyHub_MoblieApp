// Runtime-agnostic handlers for group calls: start/join (token issuance), leave, end-for-all,
// signed LiveKit webhooks and the durable revocation/reconciliation worker.
// Entry points inject Supabase + LiveKit; Node tests inject fakes. Tokens, secrets and
// provider room names are never logged; only the member's own token is returned.

export interface RpcError {
  code?: string;
  message?: string;
}
export interface RpcResult<T> {
  data: T | null;
  error: RpcError | null;
}

export type ProviderErrorKind = "unavailable" | "quota" | "not_found" | "unauthorized";
export class ProviderError extends Error {
  readonly kind: ProviderErrorKind;
  constructor(kind: ProviderErrorKind, message: string) {
    super(message);
    this.kind = kind;
  }
}

export interface ProviderWebhookEvent {
  id: string;
  event: string;
  roomName: string | null;
  roomSid: string | null;
  identity: string | null;
  createdAtSeconds: number | null;
}

export interface CallProvider {
  url: string;
  createRoom(name: string): Promise<{ sid: string }>;
  /** [name] is the member's public display name, shown on participant tiles. */
  token(room: string, identity: string, ttlSeconds: number, name: string): Promise<string>;
  /** Disconnects and revokes every token of [identity] issued before [cutoffSeconds]. */
  removeParticipant(room: string, identity: string, cutoffSeconds: number): Promise<void>;
  /** Resolves when the room is deleted or already absent. */
  deleteRoom(room: string): Promise<void>;
  /** Connected identities, or null when the room does not exist. */
  listParticipants(room: string): Promise<string[] | null>;
  /** Throws ProviderError("unauthorized") unless the body is signed by the provider. */
  verifyWebhook(body: string, authorization: string | null): Promise<ProviderWebhookEvent>;
}

export interface CallDeps {
  verifyUser(jwt: string): Promise<string | null>;
  rpc<T>(name: string, args: Record<string, unknown>): Promise<RpcResult<T>>;
  /** Created lazily so a missing provider configuration becomes a safe 503. */
  provider(): CallProvider;
  now(): number;
  /** Scheduler secret of the worker endpoint (server-only environment variable). */
  workerSecret?: string;
}

export const TOKEN_TTL_SECONDS = 300;
const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SAFE_CODES = new Set(["G0001", "G0002", "G0003", "G0004", "G0005", "G0010", "C0001", "C0002", "C0003", "C0004"]);

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}
function failure(status: number, code: string): Response {
  return json(status, { error: { code } });
}
export function rpcFailure(error: RpcError | null): Response {
  const code = error?.code && SAFE_CODES.has(error.code) ? error.code : "server_error";
  const status = ({
    G0001: 400, G0002: 401, G0003: 403, G0005: 403, G0004: 410, C0003: 410,
    G0010: 409, C0001: 409, C0002: 409, C0004: 409,
  } as Record<string, number>)[code] ?? 500;
  return failure(status, code);
}
function providerFailure(error: unknown): Response {
  const kind = error instanceof ProviderError ? error.kind : "unavailable";
  return kind === "quota" ? failure(429, "provider_quota") : failure(503, "provider_unavailable");
}
function bearer(request: Request): string | null {
  const match = /^Bearer\s+(\S+)$/i.exec(request.headers.get("authorization") ?? "");
  return match ? match[1] : null;
}
function uuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}
function constantTimeEqual(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  let diff = left.length ^ right.length;
  for (let i = 0; i < Math.max(left.length, right.length); i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  return diff === 0;
}

interface Admission {
  call: Record<string, unknown> & { id: string };
  admission: { id: string; identity: string; generation: number; room_name: string; needs_room: boolean; display_name: string | null };
}

/** POST { action: start_or_join | leave | end, ... } with the member's JWT. */
export async function handleCall(request: Request, deps: CallDeps): Promise<Response> {
  if (request.method !== "POST") return failure(405, "method_not_allowed");
  const jwt = bearer(request);
  const actor = jwt ? await deps.verifyUser(jwt).catch(() => null) : null;
  if (!actor) return failure(401, "G0002");
  let body: Record<string, unknown>;
  try {
    body = await request.json();
  } catch {
    return failure(400, "G0001");
  }
  if (!body || typeof body !== "object") return failure(400, "G0001");
  // Identity comes only from the verified JWT; user/room fields in the body are ignored.
  switch (body.action) {
    case "start_or_join":
      return await startOrJoin(actor, body, deps);
    case "leave":
    case "end": {
      if (!uuid(body.call_id)) return failure(400, "G0001");
      const name = body.action === "leave" ? "service_leave_group_call" : "service_end_group_call";
      const result = await deps.rpc<Record<string, unknown>>(name, { p_actor: actor, p_call_id: body.call_id });
      if (result.error || !result.data) return rpcFailure(result.error);
      // Wake the worker immediately; failures stay queued for the scheduler.
      await drainJobs(deps, { budgetMs: 4000 }).catch(() => undefined);
      if (body.action === "end") {
        const fresh = await deps.rpc<Record<string, unknown>>(name, { p_actor: actor, p_call_id: body.call_id });
        if (!fresh.error && fresh.data) return json(200, fresh.data);
      }
      return json(200, result.data);
    }
    default:
      return failure(400, "G0001");
  }
}

async function startOrJoin(actor: string, body: Record<string, unknown>, deps: CallDeps): Promise<Response> {
  const { group_id: groupId, request_id: requestId, client_session_id: sessionId, mode } = body;
  if (!uuid(groupId) || !uuid(requestId) || !uuid(sessionId) || (mode !== "audio" && mode !== "video")) {
    return failure(400, "G0001");
  }
  let provider: CallProvider;
  try {
    provider = deps.provider();
  } catch (error) {
    return providerFailure(error);
  }
  const admitted = await deps.rpc<Admission>("service_admit_group_call", {
    p_actor: actor, p_group_id: groupId, p_request_id: requestId, p_mode: mode, p_client_session: sessionId,
  });
  if (admitted.error || !admitted.data) return rpcFailure(admitted.error);
  const { call, admission } = admitted.data;
  const release = () => deps.rpc("service_release_group_call_admission", { p_admission_id: admission.id, p_actor: actor });

  if (admission.needs_room) {
    // Network call happens after the admission transaction committed, never inside it.
    let sid: string;
    try {
      sid = (await provider.createRoom(admission.room_name)).sid;
    } catch (error) {
      await release();
      return providerFailure(error);
    }
    const activated = await deps.rpc<{ status: string }>("service_activate_group_call", { p_call_id: call.id, p_room_sid: sid || null });
    if (activated.error || !activated.data) { await release(); return rpcFailure(activated.error); }
    if (activated.data.status !== "active") {
      await release();
      return failure(activated.data.status === "ending" ? 409 : 410, activated.data.status === "ending" ? "C0004" : "C0003");
    }
  }

  const expiresAt = new Date(deps.now() + TOKEN_TTL_SECONDS * 1000);
  let token: string;
  try {
    token = await provider.token(admission.room_name, admission.identity, TOKEN_TTL_SECONDS, admission.display_name ?? "");
  } catch {
    await release();
    return failure(500, "server_error");
  }
  // Re-check membership/generation/call state after signing; on failure the token is discarded.
  const confirmed = await deps.rpc<{ call: Record<string, unknown> }>("service_confirm_group_call_admission", {
    p_admission_id: admission.id, p_actor: actor, p_expires_at: expiresAt.toISOString(),
  });
  if (confirmed.error || !confirmed.data) return rpcFailure(confirmed.error);
  return json(200, {
    call: confirmed.data.call,
    connection: { url: provider.url, token, identity: admission.identity, expires_at: expiresAt.toISOString() },
  });
}

interface Job {
  id: number;
  kind: "revoke" | "end_room";
  call_id: string;
  room_name: string;
  identities: string[] | null;
  attempts: number;
}

/** Runs due provider jobs until none remain or the time budget is used. */
export async function drainJobs(deps: CallDeps, options: { budgetMs: number; limit?: number }) {
  const started = deps.now();
  const stats = { done: 0, failed: 0, pending: 0 };
  let provider: CallProvider | null = null;
  while (deps.now() - started < options.budgetMs) {
    const claimed = await deps.rpc<Job[]>("service_claim_group_call_jobs", { p_limit: options.limit ?? 20 });
    if (claimed.error || !claimed.data || claimed.data.length === 0) break;
    for (const job of claimed.data) {
      const identities = job.identities ?? [];
      try {
        provider ??= deps.provider();
        // Second resolution: +1 covers a token signed earlier within the current second.
        const cutoff = Math.floor(deps.now() / 1000) + 1;
        for (const identity of identities) await provider.removeParticipant(job.room_name, identity, cutoff);
        if (job.kind === "end_room") await provider.deleteRoom(job.room_name);
      } catch (error) {
        const kind = error instanceof ProviderError ? error.kind : "unavailable";
        await deps.rpc("service_fail_group_call_job", { p_job_id: job.id, p_error: `provider_${kind}` });
        stats.failed++;
        continue;
      }
      const completed = await deps.rpc<{ status: string }>("service_complete_group_call_job",
        { p_job_id: job.id, p_identities: identities });
      if (completed.data?.status === "done") stats.done++; else stats.pending++;
    }
    if (stats.failed > 0) break; // provider trouble: leave the rest to the backoff schedule
  }
  return stats;
}

/** LiveKit webhook endpoint: signature verified with the API secret, deduplicated in SQL. */
export async function handleWebhook(request: Request, deps: CallDeps): Promise<Response> {
  if (request.method !== "POST") return failure(405, "method_not_allowed");
  const body = await request.text();
  let event: ProviderWebhookEvent;
  try {
    event = await deps.provider().verifyWebhook(body, request.headers.get("authorization"));
  } catch (error) {
    return error instanceof ProviderError && error.kind === "unauthorized" ? failure(401, "unauthorized") : providerFailure(error);
  }
  if (!event.id || !event.event) return failure(400, "G0001");
  const recorded = await deps.rpc<{ duplicate: boolean; applied: string }>("service_record_group_call_event", {
    p_event_id: event.id,
    p_event: event.event,
    p_room_name: event.roomName,
    p_room_sid: event.roomSid,
    p_identity: uuid(event.identity) ? event.identity : null,
    p_created_at: event.createdAtSeconds ? new Date(event.createdAtSeconds * 1000).toISOString() : null,
  });
  if (recorded.error || !recorded.data) return failure(500, "server_error"); // provider retries delivery
  if (["revoke", "reclose", "ending"].includes(recorded.data.applied)) {
    await drainJobs(deps, { budgetMs: 4000 }).catch(() => undefined);
  }
  return json(200, { ok: true, duplicate: recorded.data.duplicate });
}

/** Scheduler/wake endpoint: reconcile active rooms (≤ every 30 s each), then drain jobs. */
export async function handleWorker(request: Request, deps: CallDeps): Promise<Response> {
  if (request.method !== "POST") return failure(405, "method_not_allowed");
  const token = bearer(request);
  if (!deps.workerSecret || deps.workerSecret.length < 32 || !token || !constantTimeEqual(token, deps.workerSecret)) {
    return failure(401, "unauthorized");
  }
  const due = await deps.rpc<{ id: string; room_name: string }[]>("service_reconcile_group_calls", { p_limit: 50 });
  if (due.error || !due.data) return failure(500, "server_error");
  let reconciled = 0;
  let providerErrors = 0;
  for (const call of due.data) {
    try {
      const identities = await deps.provider().listParticipants(call.room_name);
      const result = await deps.rpc("service_record_group_call_presence", {
        p_call_id: call.id, p_identities: (identities ?? []).filter(uuid),
      });
      if (!result.error) reconciled++;
    } catch {
      providerErrors++; // try again on the next pass
    }
  }
  const jobs = await drainJobs(deps, { budgetMs: 20000 });
  return json(200, { reconciled, provider_errors: providerErrors, jobs });
}
