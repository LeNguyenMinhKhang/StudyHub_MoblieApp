// Server-side PDF validation for group documents. Runtime-agnostic: the PDF parser is
// injected (pdf-lib 1.17.1 in both the Edge entrypoint and the Node contract tests), so
// no Deno- or Node-only import is needed here. The parser never runs embedded scripts,
// follows links or extracts attachments; it only reads the object graph.

export const MAX_PDF_BYTES = 20 * 1024 * 1024;
export const MAX_PDF_PAGES = 100;
// ISO 32000 limits a user-space page edge to 14,400 units (200 inches).
const MAX_PAGE_EDGE = 14_400;
const MIN_PAGE_EDGE = 3;

export type PdfRejection =
  | "empty"
  | "too_large"
  | "not_pdf"
  | "encrypted"
  | "corrupt"
  | "no_pages"
  | "too_many_pages"
  | "invalid_page_size";

// Erasable TypeScript only (no parameter properties/enums) so Node can strip types in tests.
export class PdfValidationError extends Error {
  readonly reason: PdfRejection;
  constructor(reason: PdfRejection) {
    super(reason);
    this.reason = reason;
    this.name = "PdfValidationError";
  }
}

/** Effective page size as rendered (crop box after /Rotate), in PDF points. */
export interface CanonicalPage {
  w: number;
  h: number;
}

export interface ValidatedPdf {
  sha256: string;
  size: number;
  pageCount: number;
  pages: CanonicalPage[];
}

interface Box {
  width: number;
  height: number;
}
interface ParsedPage {
  getCropBox(): Box;
  getMediaBox(): Box;
  getRotation(): { angle: number };
}
interface ParsedDocument {
  getPageCount(): number;
  getPages(): ParsedPage[];
}
export type PdfLoader = (
  bytes: Uint8Array,
  options: { ignoreEncryption: false; updateMetadata: false; throwOnInvalidObject: boolean },
) => Promise<ParsedDocument>;

const HEADER = /^%PDF-(1\.[0-7]|2\.0)/;

function hasPdfHeader(bytes: Uint8Array): boolean {
  // Viewers accept a header within the first 1,024 bytes; anything else is not a PDF.
  const head = new TextDecoder("latin1").decode(bytes.subarray(0, Math.min(bytes.length, 1024)));
  const at = head.indexOf("%PDF-");
  return at >= 0 && HEADER.test(head.slice(at));
}

function hasEof(bytes: Uint8Array): boolean {
  const tail = new TextDecoder("latin1").decode(bytes.subarray(Math.max(0, bytes.length - 2048)));
  return tail.includes("%%EOF");
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

/**
 * Validates untrusted bytes and returns server-derived metadata. Nothing from the client
 * (claimed size, page count, hash or MIME type) is trusted. Throws [PdfValidationError].
 */
export async function validatePdf(bytes: Uint8Array, load: PdfLoader): Promise<ValidatedPdf> {
  if (bytes.length === 0) throw new PdfValidationError("empty");
  if (bytes.length > MAX_PDF_BYTES) throw new PdfValidationError("too_large");
  if (!hasPdfHeader(bytes) || !hasEof(bytes)) throw new PdfValidationError("not_pdf");
  // pdf-lib rejects a trailer /Encrypt entry unless ignoreEncryption is set.
  let parsed: ParsedDocument;
  try {
    parsed = await load(bytes, { ignoreEncryption: false, updateMetadata: false, throwOnInvalidObject: false });
  } catch (error) {
    const name = (error as { name?: string } | null)?.name ?? "";
    const message = String((error as { message?: string } | null)?.message ?? "");
    if (name === "EncryptedPDFError" || /encrypted/i.test(message)) throw new PdfValidationError("encrypted");
    throw new PdfValidationError("corrupt");
  }
  let count: number;
  let rawPages: ParsedPage[];
  try {
    count = parsed.getPageCount();
    if (count === 0) throw new PdfValidationError("no_pages");
    if (count > MAX_PDF_PAGES) throw new PdfValidationError("too_many_pages");
    rawPages = parsed.getPages();
  } catch (error) {
    if (error instanceof PdfValidationError) throw error;
    throw new PdfValidationError("corrupt");
  }
  const pages: CanonicalPage[] = rawPages.map((page) => {
    let box: Box;
    let angle: number;
    try {
      box = page.getCropBox() ?? page.getMediaBox();
      angle = page.getRotation().angle;
    } catch {
      throw new PdfValidationError("corrupt");
    }
    const width = Math.abs(box.width);
    const height = Math.abs(box.height);
    if (!Number.isFinite(width) || !Number.isFinite(height) || !Number.isFinite(angle) ||
        width < MIN_PAGE_EDGE || height < MIN_PAGE_EDGE || width > MAX_PAGE_EDGE || height > MAX_PAGE_EDGE ||
        angle % 90 !== 0) {
      throw new PdfValidationError("invalid_page_size");
    }
    // Android PdfRenderer (PDFium) reports dimensions after /Rotate; canonical
    // coordinates are defined on that rendered orientation.
    const quarterTurn = ((angle % 360) + 360) % 360 % 180 === 90;
    return quarterTurn ? { w: round(height), h: round(width) } : { w: round(width), h: round(height) };
  });
  if (pages.length !== count) throw new PdfValidationError("corrupt");
  return { sha256: await sha256Hex(bytes), size: bytes.length, pageCount: count, pages };
}
