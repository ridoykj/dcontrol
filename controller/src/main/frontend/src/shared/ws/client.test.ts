import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RealtimeClient, RealtimeError } from "./client";

class FakeSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  static instances: FakeSocket[] = [];

  readyState = FakeSocket.CONNECTING;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;

  constructor(readonly url: string) {
    FakeSocket.instances.push(this);
  }

  open() {
    this.readyState = FakeSocket.OPEN;
    this.onopen?.();
  }

  receive(message: unknown) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  send(data: string) {
    this.sent.push(data);
  }

  close(code = 1000) {
    this.readyState = FakeSocket.CLOSED;
    this.onclose?.({ code });
  }
}

function createClient(overrides: Partial<ConstructorParameters<typeof RealtimeClient>[0]> = {}) {
  return new RealtimeClient({
    getTicket: async () => "ticket",
    url: (ticket) => `ws://test/ws/web?ticket=${ticket}`,
    WebSocketImpl: FakeSocket as unknown as typeof WebSocket,
    random: () => 0.5,
    requestTimeoutMs: 1000,
    ...overrides,
  });
}

async function connected(client: RealtimeClient): Promise<FakeSocket> {
  client.start();
  await vi.waitFor(() => expect(FakeSocket.instances.length).toBeGreaterThan(0));
  const socket = FakeSocket.instances.at(-1)!;
  socket.open();
  return socket;
}

describe("RealtimeClient", () => {
  beforeEach(() => {
    FakeSocket.instances = [];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("resolves a request with the result carrying the same requestId", async () => {
    const client = createClient();
    const socket = await connected(client);

    const promise = client.request("device.ping", { deviceId: "d1" });
    const sent = JSON.parse(socket.sent[0]!);
    expect(sent).toMatchObject({ v: 1, type: "device.ping", payload: { deviceId: "d1" } });

    socket.receive({ v: 1, type: "device.ping.result", requestId: "other", payload: { rttMs: 1 } });
    socket.receive({ v: 1, type: "device.ping.result", requestId: sent.requestId, payload: { rttMs: 12 } });

    await expect(promise).resolves.toMatchObject({ payload: { rttMs: 12 } });
    client.stop();
  });

  it("rejects with the error code of a failed result", async () => {
    const client = createClient();
    const socket = await connected(client);

    const promise = client.request("device.ping", { deviceId: "d1" });
    const { requestId } = JSON.parse(socket.sent[0]!);
    socket.receive({
      v: 1,
      type: "device.ping.result",
      requestId,
      error: { code: "DEVICE_OFFLINE", message: "Device is offline" },
    });

    await expect(promise).rejects.toMatchObject({ code: "DEVICE_OFFLINE" });
    client.stop();
  });

  it("keeps the missing phone permission of a PERMISSION_REQUIRED result", async () => {
    const client = createClient();
    const socket = await connected(client);

    const promise = client.request("sms.threads", { deviceId: "d1" });
    const { requestId } = JSON.parse(socket.sent[0]!);
    socket.receive({
      v: 1,
      type: "sms.threads.result",
      requestId,
      error: { code: "PERMISSION_REQUIRED", message: "Grant SMS", details: { permission: "SMS" } },
    });

    await expect(promise).rejects.toMatchObject({ code: "PERMISSION_REQUIRED", permission: "SMS" });
    client.stop();
  });

  it("times out unanswered requests", async () => {
    const client = createClient();
    await connected(client);
    vi.useFakeTimers();

    const promise = client.request("device.ping", {});
    vi.advanceTimersByTime(1001);

    await expect(promise).rejects.toBeInstanceOf(RealtimeError);
    await expect(promise).rejects.toMatchObject({ code: "TIMEOUT" });
    client.stop();
  });

  it("lets a long-running command wait longer than the default timeout", async () => {
    const client = createClient();
    const socket = await connected(client);
    vi.useFakeTimers();

    const promise = client.request("camera.capture", {}, 5000);
    vi.advanceTimersByTime(1001);
    const { requestId } = JSON.parse(socket.sent.at(-1)!) as { requestId: string };
    socket.receive({ v: 1, type: "camera.capture.result", requestId, payload: { mediaId: "m1" } });

    await expect(promise).resolves.toMatchObject({ payload: { mediaId: "m1" } });
    client.stop();
  });

  it("sends fire-and-forget messages without a requestId only while open", async () => {
    const client = createClient();
    expect(client.send("screen.stop", { sessionId: "s1" })).toBe(false);
    const socket = await connected(client);

    expect(client.send("screen.stop", { sessionId: "s1" })).toBe(true);
    expect(JSON.parse(socket.sent[0]!)).toEqual({ v: 1, type: "screen.stop", payload: { sessionId: "s1" } });
    client.stop();
  });

  it("dispatches pushed messages to subscribers", async () => {
    const client = createClient();
    const socket = await connected(client);
    const listener = vi.fn();
    client.subscribe(listener);

    socket.receive({ v: 1, type: "device.status", payload: { deviceId: "d1" } });
    socket.receive({ v: 2, type: "ignored" });

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0].type).toBe("device.status");
    client.stop();
  });

  it("reconnects with full-jitter exponential backoff", () => {
    const client = createClient({ random: () => 0.999, maxBackoffMs: 30_000 });
    expect(client.backoffDelay(0)).toBe(999);
    expect(client.backoffDelay(3)).toBe(7992);
    expect(client.backoffDelay(10)).toBe(29_970);
    expect(createClient({ random: () => 0 }).backoffDelay(5)).toBe(0);
  });

  it("opens a new socket after the connection drops and fails pending requests", async () => {
    const client = createClient();
    const socket = await connected(client);
    const pending = client.request("device.ping", {});

    socket.close(1006);

    await expect(pending).rejects.toMatchObject({ code: "DISCONNECTED" });
    await vi.waitFor(() => expect(FakeSocket.instances).toHaveLength(2), { timeout: 2000 });
    expect(client.connectionState).toBe("connecting");
    client.stop();
  });
});
