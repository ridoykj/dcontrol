/** Saves an already-fetched file; the bytes are in memory, so no download ticket is needed. */
export function saveBlob(blob: Blob, fileName: string, doc: Document = document) {
  const url = URL.createObjectURL(blob);
  const anchor = doc.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = "none";
  doc.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
