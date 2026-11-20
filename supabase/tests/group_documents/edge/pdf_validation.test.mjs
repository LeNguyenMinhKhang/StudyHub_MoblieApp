import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { PDFDocument } from "pdf-lib";
import { MAX_PDF_BYTES, validatePdf } from "../../../functions/_shared/pdf_validation.ts";
import { emptyPdf, encryptedPdf, mixedPdf, paddedPdf, vectorPdf } from "./fixtures.mjs";

const load = (bytes, options) => PDFDocument.load(bytes, options);

async function rejects(bytes, reason) {
  await assert.rejects(validatePdf(bytes, load), (error) => {
    assert.equal(error.name, "PdfValidationError");
    assert.equal(error.reason, reason);
    return true;
  });
}

test("accepts a vector PDF and derives hash, size and page sizes on the server", async () => {
  const bytes = await vectorPdf({ pages: 3 });
  const result = await validatePdf(bytes, load);
  assert.equal(result.pageCount, 3);
  assert.equal(result.size, bytes.length);
  assert.equal(result.sha256, createHash("sha256").update(bytes).digest("hex"));
  assert.deepEqual(result.pages[0], { w: 595.28, h: 841.89 });
});

test("reports rendered orientation for rotated and cropped pages", async () => {
  const result = await validatePdf(await mixedPdf(), load);
  assert.deepEqual(result.pages, [
    { w: 612, h: 792 },
    { w: 595, h: 842 },  // 842x595 rotated 90
    { w: 300, h: 500 },  // 180 keeps orientation
    { w: 400, h: 200 },  // crop 200x400 rotated 270
  ]);
});

test("accepts exactly 100 pages and rejects 101", async () => {
  assert.equal((await validatePdf(await vectorPdf({ pages: 100, size: [200, 200] }), load)).pageCount, 100);
  await rejects(await vectorPdf({ pages: 101, size: [200, 200] }), "too_many_pages");
});

test("rejects empty, encrypted, zero-page, non-PDF and corrupt input", async () => {
  await rejects(new Uint8Array(), "empty");
  await rejects(await encryptedPdf(), "encrypted");
  await rejects(await emptyPdf(), "no_pages");
  const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...new Array(200).fill(1)]);
  await rejects(png, "not_pdf");
  // Extension or MIME claims are irrelevant: a renamed text file stays rejected.
  await rejects(new TextEncoder().encode("hello.pdf\n%%EOF"), "not_pdf");
  // Header without an end-of-file marker (truncated upload).
  const valid = await vectorPdf();
  await rejects(valid.subarray(0, Math.floor(valid.length / 2)), "not_pdf");
  // Header and EOF but garbage body: no page tree can be recovered.
  const garbage = new TextEncoder().encode("%PDF-1.7\n" + "x".repeat(4000) + "\n%%EOF\n");
  await assert.rejects(validatePdf(garbage, load), (e) => ["corrupt", "no_pages"].includes(e.reason));
  await rejects(await vectorPdf({ size: [1, 1] }), "invalid_page_size");
});

test("enforces the 20 MiB limit before parsing and accepts a file just under it", async () => {
  const oversized = new Uint8Array(MAX_PDF_BYTES + 1);
  let parsed = false;
  await assert.rejects(validatePdf(oversized, async () => { parsed = true; }), (e) => e.reason === "too_large");
  assert.equal(parsed, false);
  const near = await paddedPdf(MAX_PDF_BYTES - 64 * 1024);
  assert.ok(near.length <= MAX_PDF_BYTES && near.length > MAX_PDF_BYTES - 256 * 1024, String(near.length));
  const started = performance.now();
  const result = await validatePdf(near, load);
  const elapsed = performance.now() - started;
  assert.equal(result.pageCount, 1);
  // Resource evidence only (local Node, not the Edge isolate): logged for the spike record.
  console.log(`near-limit ${near.length} bytes validated in ${elapsed.toFixed(0)} ms, heap ${(process.memoryUsage().heapUsed / 1048576).toFixed(0)} MiB`);
});
