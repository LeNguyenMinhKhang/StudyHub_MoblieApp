// Fake-dependency contract tests for the Edge handlers (no Supabase, Deno or network).
import { test } from "node:test";
import assert from "node:assert/strict";
import { PDFDocument } from "pdf-lib";
import { handleCleanup, handleFinalize } from "../../../functions/_shared/group_documents.ts";
import { encryptedPdf, mixedPdf } from "./fixtures.mjs";

const UPLOAD = "11111111-1111-4111-8111-111111111111";
const ACTOR = "22222222-2222-4222-8222-222222222222";
const KEY = "33333333-3333-4333-8333-333333333333/" + UPLOAD + ".pdf";

function fakes({ status = "pending", object, prepareError, finalizeError, removeError } = {}) {
  const calls = [];
  const removed = [];
  const deps = {
    verifyUser: async (jwt) => (jwt === "valid-jwt" ? ACTOR : null),
    rpc: async (name, args) => {
      calls.push([name, args]);
      const projection = { id: UPLOAD, group_id: "g", document_id: null, version_id: null, bucket: "group-documents",
        object_key: KEY, status, failure_reason: null, expires_at: "2026-11-17T00:00:00Z" };
      if (name === "service_prepare_group_document_validation") {
        return prepareError ? { data: null, error: prepareError } : { data: projection, error: null };
      }
      if (name === "service_finalize_group_document_upload") {
        return finalizeError ? { data: null, error: finalizeError }
          : { data: { ...projection, status: "ready", version_id: UPLOAD, document_id: "d" }, error: null };
      }
      if (name === "service_fail_group_document_upload") {
        return { data: { ...projection, status: "failed", failure_reason: args.p_reason }, error: null };
      }
      if (name === "service_claim_group_document_cleanup") {
        return { data: [{ upload_id: UPLOAD, object_key: KEY }], error: null };
      }
      if (name === "service_confirm_group_document_cleanup") return { data: args.p_upload_ids.length, error: null };
      throw new Error("unexpected rpc " + name);
    },
    download: async () => (object === undefined ? null : object),
    remove: async (bucket, keys) => { removed.push(...keys); return { error: removeError ?? null }; },
    loadPdf: (bytes, options) => PDFDocument.load(bytes, options),
  };
  return { deps, calls, removed };
}

function post(body, token = "valid-jwt") {
  return new Request("https://edge.test/group-document-finalize", {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

test("finalize validates server-side and passes only server-derived metadata", async () => {
  const bytes = await mixedPdf();
  const { deps, calls } = fakes({ object: bytes });
  const response = await handleFinalize(post({ upload_id: UPLOAD, page_count: 1, sha256: "forged" }), deps);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, "ready");
  const [, args] = calls.find(([name]) => name === "service_finalize_group_document_upload");
  assert.equal(args.p_actor, ACTOR);
  assert.equal(args.p_size_bytes, bytes.length);
  assert.equal(args.p_pages.length, 4);
  assert.match(args.p_content_hash, /^[0-9a-f]{64}$/);
  assert.notEqual(args.p_content_hash, "forged");
});

test("finalize rejects missing or forged sessions and malformed ids before any RPC", async () => {
  for (const [request, status] of [[post({ upload_id: UPLOAD }, null), 401], [post({ upload_id: UPLOAD }, "forged"), 401],
    [post({ upload_id: "not-a-uuid" }), 400], [post("{"), 400],
    [new Request("https://edge.test", { method: "GET" }), 405]]) {
    const { deps, calls } = fakes({ object: await mixedPdf() });
    const response = await handleFinalize(request, deps);
    assert.equal(response.status, status);
    assert.equal(calls.length, 0);
  }
});

test("invalid PDFs are failed, removed and reported with a stable reason", async () => {
  const { deps, calls, removed } = fakes({ object: await encryptedPdf() });
  const response = await handleFinalize(post({ upload_id: UPLOAD }), deps);
  assert.equal(response.status, 422);
  assert.deepEqual(await response.json(), { error: { code: "invalid_pdf", reason: "encrypted" } });
  assert.ok(calls.some(([name, args]) => name === "service_fail_group_document_upload" && args.p_reason === "encrypted"));
  assert.ok(!calls.some(([name]) => name === "service_finalize_group_document_upload"));
  assert.deepEqual(removed, [KEY]);
});

test("ready retry is idempotent; missing object keeps the reservation pending", async () => {
  let { deps, calls } = fakes({ status: "ready" });
  let response = await handleFinalize(post({ upload_id: UPLOAD }), deps);
  assert.equal(response.status, 200);
  assert.equal(calls.length, 1);
  ({ deps, calls } = fakes({}));
  response = await handleFinalize(post({ upload_id: UPLOAD }), deps);
  assert.equal(response.status, 409);
  assert.deepEqual(calls.map(([name]) => name), ["service_prepare_group_document_validation"]);
  ({ deps } = fakes({ status: "expired" }));
  assert.equal((await handleFinalize(post({ upload_id: UPLOAD }), deps)).status, 410);
});

test("permission loss and SQL errors map to stable codes without leaking messages", async () => {
  let { deps } = fakes({ object: await mixedPdf(), finalizeError: { code: "D0005", message: "upload unavailable (secret detail)" } });
  let response = await handleFinalize(post({ upload_id: UPLOAD }), deps);
  assert.equal(response.status, 410);
  assert.deepEqual(await response.json(), { error: { code: "D0005" } });
  ({ deps } = fakes({ prepareError: { code: "XX000", message: "relation public.x does not exist" } }));
  response = await handleFinalize(post({ upload_id: UPLOAD }), deps);
  assert.equal(response.status, 500);
  assert.deepEqual(await response.json(), { error: { code: "server_error" } });
  ({ deps } = fakes({ prepareError: { code: "G0003" } }));
  assert.equal((await handleFinalize(post({ upload_id: UPLOAD }), deps)).status, 403);
});

test("cleanup requires the scheduler secret and confirms only after storage removal", async () => {
  const secret = "s".repeat(40);
  const call = (token, extra = {}) => {
    const f = fakes(extra);
    const request = new Request("https://edge.test/cleanup", { method: "POST",
      headers: token ? { authorization: `Bearer ${token}` } : {} });
    return [handleCleanup(request, { rpc: f.deps.rpc, remove: f.deps.remove, secret }), f];
  };
  for (const token of [null, "wrong", secret + "x"]) {
    const [promise, f] = call(token);
    assert.equal((await promise).status, 401);
    assert.equal(f.calls.length, 0);
  }
  let [promise, f] = call(secret);
  let response = await promise;
  assert.deepEqual(await response.json(), { removed: 1 });
  assert.deepEqual(f.removed, [KEY]);
  [promise, f] = call(secret, { removeError: { message: "down" } });
  assert.equal((await promise).status, 503);
  assert.ok(!f.calls.some(([name]) => name === "service_confirm_group_document_cleanup"));
  const weak = await handleCleanup(new Request("https://edge.test", { method: "POST", headers: { authorization: "Bearer short" } }),
    { rpc: async () => assert.fail("no rpc"), remove: async () => assert.fail("no remove"), secret: "short" });
  assert.equal(weak.status, 401);
});
