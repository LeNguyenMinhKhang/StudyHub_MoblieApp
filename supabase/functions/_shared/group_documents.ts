// Runtime-agnostic handlers for group document finalization and orphan cleanup.
// Edge entrypoints inject Supabase/pdf-lib; Node contract tests inject fakes.
// Secrets and object bytes are never logged or returned to the client.

import { PdfValidationError, validatePdf } from "./pdf_validation.ts";
import type { PdfLoader } from "./pdf_validation.ts";

export const BUCKET = "group-documents";

export interface RpcError {
  code?: string;
  message?: string;
}
export interface RpcResult<T> {
  data: T | null;
  error: RpcError | null;
}
export interface UploadProjection {
  id: string;
  group_id: string;
  document_id: string | null;
  version_id: string | null;
  bucket: string;
  object_key: string;
  status: "pending" | "ready" | "failed" | "expired";
  failure_reason: string | null;
  expires_at: string;
}

export interface FinalizeDeps {
  /** Verifies the end-user JWT with Supabase Auth and returns the user id, or null. */
  verifyUser(jwt: string): Promise<string | null>;
  rpc<T>(name: string, args: Record<string, unknown>): Promise<RpcResult<T>>;
  /** Service-role download; null when the object does not exist. */
  download(bucket: string, key: string): Promise<Uint8Array | null>;
  remove(bucket: string, keys: string[]): Promise<{ error: RpcError | null }>;
  loadPdf: PdfLoader;
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// Only stable domain codes cross the boundary; anything else becomes a generic failure.
const SAFE_CODES = new Set(["G0001", "G0002", "G0003", "G0004", "G0005", "G0010", "D0001", "D0005"]);

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}
function failure(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return json(status, { error: { code, ...extra } });
}
function rpcFailure(error: RpcError): Response {
  const code = error.code && SAFE_CODES.has(error.code) ? error.code : "server_error";
  const status = code === "server_error" ? 500
    : code === "G0001" ? 400
    : code === "G0002" ? 401
    : code === "G0003" || code === "G0005" ? 403
    : code === "G0010" ? 409
    : 410; // unavailable/expired: never reveals whether another user's upload exists
  return failure(status, code);
}

function bearer(request: Request): string | null {
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(\S+)$/i.exec(header);
  return match ? match[1] : null;
}

export async function handleFinalize(request: Request, deps: FinalizeDeps): Promise<Response> {
  if (request.method !== "POST") return failure(405, "method_not_allowed");
  const jwt = bearer(request);
  const actor = jwt ? await deps.verifyUser(jwt).catch(() => null) : null;
  if (!actor) return failure(401, "G0002");
  let uploadId: unknown;
  try {
    uploadId = (await request.json())?.upload_id;
  } catch {
    return failure(400, "G0001");
  }
  if (typeof uploadId !== "string" || !UUID.test(uploadId)) return failure(400, "G0001");

  // Authorize before touching bytes: actor must own a live reservation in an active group.
  const prepared = await deps.rpc<UploadProjection>("service_prepare_group_document_validation",
    { p_upload_id: uploadId, p_actor: actor });
  if (prepared.error || !prepared.data) return rpcFailure(prepared.error ?? {});
  const upload = prepared.data;
  if (upload.status === "ready") return json(200, upload); // lost response: idempotent
  if (upload.status !== "pending") return failure(410, "D0005", { status: upload.status });

  let bytes: Uint8Array | null;
  try {
    bytes = await deps.download(BUCKET, upload.object_key);
  } catch {
    return failure(503, "storage_unavailable");
  }
  // Missing object: the client may still be uploading; keep the reservation pending.
  if (!bytes) return failure(409, "object_missing");

  let validated;
  try {
    validated = await validatePdf(bytes, deps.loadPdf);
  } catch (error) {
    if (!(error instanceof PdfValidationError)) return failure(500, "server_error");
    const failed = await deps.rpc<UploadProjection>("service_fail_group_document_upload",
      { p_upload_id: upload.id, p_actor: actor, p_reason: error.reason });
    // Best effort; the cleanup worker retries removal of failed objects.
    await deps.remove(BUCKET, [upload.object_key]).catch(() => undefined);
    if (failed.error) return rpcFailure(failed.error);
    return failure(422, "invalid_pdf", { reason: error.reason });
  }

  // Finalize rechecks membership/permission under the group lock in one transaction.
  const finalized = await deps.rpc<UploadProjection>("service_finalize_group_document_upload", {
    p_upload_id: upload.id,
    p_actor: actor,
    p_content_hash: validated.sha256,
    p_size_bytes: validated.size,
    p_pages: validated.pages,
  });
  if (finalized.error || !finalized.data) return rpcFailure(finalized.error ?? {});
  return json(200, finalized.data);
}

export interface CleanupDeps {
  rpc<T>(name: string, args: Record<string, unknown>): Promise<RpcResult<T>>;
  remove(bucket: string, keys: string[]): Promise<{ error: RpcError | null }>;
  /** Shared scheduler secret (server-only environment variable). */
  secret: string | undefined;
}

function constantTimeEqual(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  let diff = left.length ^ right.length;
  for (let i = 0; i < Math.max(left.length, right.length); i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  return diff === 0;
}

/** Removes expired/failed pending objects. Ready objects are never claimed by SQL. */
export async function handleCleanup(request: Request, deps: CleanupDeps): Promise<Response> {
  if (request.method !== "POST") return failure(405, "method_not_allowed");
  const token = bearer(request);
  if (!deps.secret || deps.secret.length < 32 || !token || !constantTimeEqual(token, deps.secret)) {
    return failure(401, "unauthorized");
  }
  const claimed = await deps.rpc<{ upload_id: string; object_key: string }[]>(
    "service_claim_group_document_cleanup", { p_limit: 100 });
  if (claimed.error || !claimed.data) return failure(500, "server_error");
  if (claimed.data.length === 0) return json(200, { removed: 0 });
  const removal = await deps.remove(BUCKET, claimed.data.map((row) => row.object_key));
  // Storage removal of an absent key is a no-op; any other error leaves rows for the next run.
  if (removal.error) return failure(503, "storage_unavailable");
  const confirmed = await deps.rpc<number>("service_confirm_group_document_cleanup",
    { p_upload_ids: claimed.data.map((row) => row.upload_id) });
  if (confirmed.error) return failure(500, "server_error");
  return json(200, { removed: confirmed.data ?? 0 });
}
