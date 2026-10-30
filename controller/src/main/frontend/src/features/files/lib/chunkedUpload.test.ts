import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api/problem";
import type { Transfer } from "../schemas/filesSchemas";
import { uploadInChunks, type ChunkTransport } from "./chunkedUpload";

function transfer(receivedBytes: number, size: number, state: Transfer["state"] = "RECEIVING"): Transfer {
  return {
    id: "t1",
    deviceId: "d1",
    direction: "TO_DEVICE",
    state,
    fileName: "f.bin",
    devicePath: "/storage/emulated/0/Download",
    size,
    receivedBytes,
    sha256: null,
    errorCode: null,
    createdAt: "2026-10-29T10:00:00Z",
    expiresAt: "2026-10-29T10:15:00Z",
    finishedAt: null,
  };
}

/** A server that stores bytes and enforces the offset rule like the controller. */
function fakeServer(size: number, failures: { networkAt?: number[] } = {}) {
  let received = 0;
  let calls = 0;
  const starts: number[] = [];
  const transport: ChunkTransport = {
    putChunk: vi.fn(async (_id, chunk: Blob, start: number) => {
      calls += 1;
      starts.push(start);
      if (failures.networkAt?.includes(calls)) {
        received = start + Math.floor(chunk.size / 2); // partial write the client doesn't know about
        received = start; // the controller discards partial chunks
        throw new TypeError("Failed to fetch");
      }
      if (start !== received) {
        throw new ApiError(409, { code: "CONFLICT", detail: "offset", receivedBytes: received });
      }
      received += chunk.size;
      return transfer(received, size, received === size ? "READY" : "RECEIVING");
    }),
    getTransfer: vi.fn(async () => transfer(received, size, received === size ? "READY" : "RECEIVING")),
  };
  return { transport, starts: () => starts };
}

const noSleep = () => Promise.resolve();

describe("uploadInChunks", () => {
  it("uploads in chunks and reports progress", async () => {
    const file = new Blob([new Uint8Array(10)]);
    const server = fakeServer(10);
    const progress: number[] = [];
    const done = await uploadInChunks(file, transfer(0, 10), server.transport, {
      chunkSize: 4,
      onProgress: (bytes) => progress.push(bytes),
      sleep: noSleep,
    });
    expect(done.state).toBe("READY");
    expect(server.starts()).toEqual([0, 4, 8]);
    expect(progress).toEqual([4, 8, 10]);
  });

  it("resumes from the server offset after a network failure", async () => {
    const file = new Blob([new Uint8Array(10)]);
    const server = fakeServer(10, { networkAt: [2] });
    const done = await uploadInChunks(file, transfer(0, 10), server.transport, { chunkSize: 4, sleep: noSleep });
    expect(done.state).toBe("READY");
    expect(server.transport.getTransfer).toHaveBeenCalledOnce();
    expect(server.starts()).toEqual([0, 4, 4, 8]);
  });

  it("continues where the server says on a 409 conflict", async () => {
    const file = new Blob([new Uint8Array(8)]);
    const server = fakeServer(8);
    await server.transport.putChunk("t1", new Blob([new Uint8Array(4)]), 0, 8);
    const done = await uploadInChunks(file, transfer(0, 8), server.transport, { chunkSize: 4, sleep: noSleep });
    expect(done.state).toBe("READY");
    expect(server.starts()).toEqual([0, 0, 4]);
  });

  it("starts at the transfer's received bytes", async () => {
    const file = new Blob([new Uint8Array(8)]);
    const server = fakeServer(8);
    await server.transport.putChunk("t1", new Blob([new Uint8Array(4)]), 0, 8);
    await uploadInChunks(file, transfer(4, 8), server.transport, { chunkSize: 4, sleep: noSleep });
    expect(server.starts()).toEqual([0, 4]);
  });

  it("gives up on client errors and after too many retries", async () => {
    const file = new Blob([new Uint8Array(4)]);
    const rejecting: ChunkTransport = {
      putChunk: vi.fn(async () => {
        throw new ApiError(413, { code: "PAYLOAD_TOO_LARGE" });
      }),
      getTransfer: vi.fn(),
    };
    await expect(uploadInChunks(file, transfer(0, 4), rejecting, { sleep: noSleep })).rejects.toMatchObject({
      code: "PAYLOAD_TOO_LARGE",
    });
    expect(rejecting.putChunk).toHaveBeenCalledOnce();

    const flaky: ChunkTransport = {
      putChunk: vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
      getTransfer: vi.fn(async () => transfer(0, 4)),
    };
    await expect(uploadInChunks(file, transfer(0, 4), flaky, { maxRetries: 2, sleep: noSleep })).rejects.toThrow(
      "Failed to fetch",
    );
    expect(flaky.putChunk).toHaveBeenCalledTimes(3);
  });

  it("stops when cancelled", async () => {
    const controller = new AbortController();
    const server = fakeServer(8);
    controller.abort();
    await expect(
      uploadInChunks(new Blob([new Uint8Array(8)]), transfer(0, 8), server.transport, {
        signal: controller.signal,
        sleep: noSleep,
      }),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect(server.transport.putChunk).not.toHaveBeenCalled();
  });
});
