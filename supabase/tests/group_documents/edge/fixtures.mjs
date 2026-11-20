// Deterministic PDF fixtures built with the pinned pdf-lib; no network or real documents.
import { PDFDocument, PDFName, StandardFonts, degrees } from "pdf-lib";

export async function vectorPdf({ pages = 1, size = [595.28, 841.89], rotate = 0, crop = null } = {}) {
  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  for (let i = 0; i < pages; i++) {
    const page = doc.addPage(size);
    page.drawText(`Page ${i + 1}`, { x: 40, y: size[1] - 60, size: 18, font });
    page.drawRectangle({ x: 40, y: 40, width: 60, height: 30 });
    if (rotate) page.setRotation(degrees(rotate));
    if (crop) page.setCropBox(...crop);
  }
  return doc.save({ useObjectStreams: true });
}

export async function mixedPdf() {
  const doc = await PDFDocument.create();
  doc.addPage([612, 792]);
  doc.addPage([842, 595]).setRotation(degrees(90));
  doc.addPage([300, 500]).setRotation(degrees(180));
  const cropped = doc.addPage([600, 600]);
  cropped.setCropBox(100, 50, 200, 400);
  cropped.setRotation(degrees(270));
  return doc.save();
}

export async function emptyPdf() {
  return (await PDFDocument.create()).save({ addDefaultPage: false });
}

export async function encryptedPdf() {
  const doc = await PDFDocument.create();
  doc.addPage();
  const encrypt = doc.context.obj({ Filter: "Standard", V: 1, R: 2, P: -4,
    O: doc.context.obj(new Array(32).fill(0)), U: doc.context.obj(new Array(32).fill(0)) });
  doc.context.trailerInfo.Encrypt = doc.context.register(encrypt);
  return doc.save({ useObjectStreams: false });
}

/** A single-page PDF padded with one uncompressed binary stream to approach a byte size. */
export async function paddedPdf(targetBytes, pages = 1) {
  const doc = await PDFDocument.create();
  for (let i = 0; i < pages; i++) doc.addPage();
  const base = (await doc.save()).length;
  const padding = new Uint8Array(Math.max(0, targetBytes - base - 400));
  for (let i = 0; i < padding.length; i++) padding[i] = (i * 2654435761) >>> 24;
  const ref = doc.context.register(doc.context.stream(padding));
  doc.getPage(0).node.set(PDFName.of("StudyHubPadding"), ref);
  return doc.save({ useObjectStreams: false });
}
