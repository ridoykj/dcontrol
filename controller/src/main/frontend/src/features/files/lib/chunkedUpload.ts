import { ApiError } from "@/shared/api/problem";
import type { Transfer } from "../schemas/filesSchemas";

export const CHUNK_SIZE = 8 * 1024 * 1024;

export type ChunkTransport = {
  putChunk(id: string, chunk: Blob, start: number, total: number, signal?: AbortSignal): Promise<Transfer>;
  getTransfer(id: string): Promise<Transfer>;
};

export type UploadOptions = {
  chunkSize?: number;
  /** Consecutive failed attempts tolerated before giving up. */
  maxRetries?: number;
  onProgress?: (uploadedBytes: number) => void;
  signal?: AbortSignal;
  sleep?: (ms: number) => Promise<void>;
};

function abortError(): DOMException {
  return new DOMException("Upload cancelled", "AbortError");
}

function isRetryable(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true; // network failure
  return error.status >= 500 || error.status === 408 || error.status === 429;
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Uploads {@code file} into a TO_DEVICE transfer chunk by chunk, starting at the server's
 * {@code receivedBytes}. Network errors and 5xx are retried with backoff after re-reading the offset from
 * the server; a 409 CONFLICT carries the offset to resume from. Resolves with the transfer once the
 * server has the whole file (state READY).
 */
export async function uploadInChunks(
  file: Blob,
  transfer: Transfer,
  transport: ChunkTransport,
  options: UploadOptions = {},
): Promise<Transfer> {
  const { chunkSize = CHUNK_SIZE, maxRetries = 5, onProgress, signal, sleep = defaultSleep } = options;
  const total = file.size;
  let current = transfer;
  let offset = transfer.receivedBytes;
  let failures = 0;

  while (current.state === "RECEIVING" && offset < total) {
    if (signal?.aborted) throw abortError();
    const end = Math.min(offset + chunkSize, total);
    try {
      current = await transport.putChunk(transfer.id, file.slice(offset, end), offset, total, signal);
      offset = current.receivedBytes;
      failures = 0;
      onProgress?.(offset);
    } catch (error) {
      if (signal?.aborted) throw abortError();
      failures += 1;
      if (failures > maxRetries) throw error;
      if (error instanceof ApiError && error.code === "CONFLICT" && error.receivedBytes !== undefined) {
        offset = error.receivedBytes;
        continue;
      }
      if (!isRetryable(error)) throw error;
      await sleep(Math.min(30_000, 500 * 2 ** (failures - 1)));
      try {
        current = await transport.getTransfer(transfer.id);
        offset = current.receivedBytes;
      } catch {
        // keep the last known offset; the next chunk attempt will tell
      }
    }
  }
  return current;
}
