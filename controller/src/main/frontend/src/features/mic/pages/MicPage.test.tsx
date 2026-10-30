import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deviceKeys } from "@/features/devices/api/devicesApi";
import type { Device } from "@/features/devices/schemas/deviceSchemas";
import { api } from "@/shared/api/client";
import { type Envelope, RealtimeError, type RealtimeClient } from "@/shared/ws/client";
import { RealtimeClientProvider } from "@/shared/ws/RealtimeProvider";
import { MicPage } from "./MicPage";

class FakePeerConnection {
  static instances: FakePeerConnection[] = [];
  iceGatheringState = "complete";
  connectionState = "new";
  localDescription: RTCSessionDescriptionInit | null = null;
  remoteDescription: RTCSessionDescriptionInit | null = null;
  ontrack: ((event: unknown) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  close = vi.fn();

  constructor(readonly config: RTCConfiguration) {
    FakePeerConnection.instances.push(this);
  }

  async setRemoteDescription(description: RTCSessionDescriptionInit) {
    this.remoteDescription = description;
  }

  async createAnswer() {
    return { type: "answer", sdp: "answer-sdp" };
  }

  async setLocalDescription(description: RTCSessionDescriptionInit) {
    this.localDescription = description;
  }

  addEventListener() {}

  removeEventListener() {}

  async getStats() {
    return new Map();
  }
}

type Handler = (type: string, payload: unknown) => Promise<unknown>;

function fakeClient(handler: Handler) {
  const listeners = new Set<(message: Envelope) => void>();
  const client = {
    request: vi.fn(async (type: string, payload: unknown, _timeoutMs?: number) => ({
      v: 1 as const,
      type: `${type}.result`,
      payload: await handler(type, payload),
    })),
    send: vi.fn(() => true),
    subscribe: (listener: (message: Envelope) => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    onStateChange: () => () => {},
  };
  const push = (type: string, payload: unknown) =>
    act(() => listeners.forEach((listener) => listener({ v: 1, type, payload })));
  return { client, push };
}

const startResult = { sessionId: "s1", iceServers: [{ urls: ["stun:localhost:3478"], username: null }] };

const clip = {
  mediaId: "m1",
  mime: "audio/mp4",
  bytes: 4096,
  durationMs: 30_000,
  capturedAt: "2026-10-30T10:15:30Z",
};

function renderPage(handler: Handler) {
  const fake = fakeClient(handler);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  queryClient.setQueryData(deviceKeys.detail("d1"), { id: "d1", status: { online: true } } as unknown as Device);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RealtimeClientProvider client={fake.client as unknown as RealtimeClient}>
        <MicPage deviceId="d1" />
      </RealtimeClientProvider>
    </QueryClientProvider>,
  );
  return { ...fake, view };
}

function state(value: string, extra: Record<string, unknown> = {}) {
  return { deviceId: "d1", sessionId: "s1", state: value, reason: null, ...extra };
}

describe("MicPage", () => {
  beforeEach(() => {
    FakePeerConnection.instances = [];
    vi.stubGlobal("RTCPeerConnection", FakePeerConnection);
    vi.stubGlobal(
      "MediaStream",
      class {
        addTrack() {}
      },
    );
    URL.createObjectURL = vi.fn(() => "blob:clip");
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(api, "requestBlob").mockResolvedValue(new Blob(["aac"], { type: "audio/mp4" }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("starts a session, answers the phone's offer and goes live", async () => {
    const { client, push } = renderPage(async (type) => {
      if (type === "mic.start") return startResult;
      throw new Error(type);
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request microphone" }));
    expect(client.request).toHaveBeenCalledWith("mic.start", { deviceId: "d1" });

    push("mic.state", state("AWAITING_CONSENT"));
    expect(await screen.findByText(/Waiting for someone to accept/)).toBeInTheDocument();

    push("mic.signal", { deviceId: "other", sessionId: "s1", kind: "OFFER", sdp: "not-mine" });
    push("mic.signal", { deviceId: "d1", sessionId: "old", kind: "OFFER", sdp: "stale" });
    push("mic.signal", { deviceId: "d1", sessionId: "s1", kind: "OFFER", sdp: "offer-sdp" });
    await waitFor(() =>
      expect(client.send).toHaveBeenCalledWith("mic.signal", { sessionId: "s1", kind: "ANSWER", sdp: "answer-sdp" }),
    );
    expect(FakePeerConnection.instances).toHaveLength(1);
    expect(FakePeerConnection.instances[0]!.remoteDescription).toEqual({ type: "offer", sdp: "offer-sdp" });
    expect(FakePeerConnection.instances[0]!.config.iceServers).toEqual([{ urls: ["stun:localhost:3478"] }]);

    push("mic.state", state("LIVE"));
    expect(screen.getByRole("meter", { name: "Input level" })).toBeInTheDocument();
    expect(screen.getByLabelText("Microphone audio")).toBeInTheDocument();
  });

  it("records a clip of the chosen length and lists it", async () => {
    let finish: (value: unknown) => void = () => {};
    const { client, push } = renderPage(async (type) => {
      if (type === "mic.start") return startResult;
      if (type === "mic.capture") return new Promise((resolve) => (finish = resolve));
      throw new Error(type);
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request microphone" }));
    await waitFor(() => expect(client.request).toHaveBeenCalledTimes(1));
    push("mic.state", state("LIVE"));

    await user.click(screen.getByRole("button", { name: /Clip length/ }));
    await user.click(await screen.findByRole("option", { name: "10 s" }));
    await user.click(screen.getByRole("button", { name: "Record clip" }));
    expect(client.request).toHaveBeenCalledWith("mic.capture", { sessionId: "s1", durationMs: 10_000 }, 370_000);
    expect(screen.getByRole("button", { name: "Recording…" })).toBeDisabled();

    await act(async () => finish(clip));
    const list = await screen.findByRole("list", { name: "Clips" });
    expect(within(list).getByText(/^Clip, 30 s/)).toBeInTheDocument();
    expect(api.requestBlob).toHaveBeenCalledWith("/api/devices/d1/mic/media/m1");
    await waitFor(() => expect(within(list).getByRole("button", { name: "Download" })).toBeEnabled());
  });

  it("points at the Microphone card when the phone lacks the microphone permission", async () => {
    renderPage(async () => {
      throw new RealtimeError("PERMISSION_REQUIRED", "Microphone permission missing", "MICROPHONE");
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request microphone" }));
    expect(await screen.findByText(/hasn't allowed dcontrol to use the microphone/)).toBeInTheDocument();
  });

  it("explains a CONFLICT from another session using the microphone", async () => {
    renderPage(async () => {
      throw new RealtimeError("CONFLICT", "busy");
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request microphone" }));
    expect(await screen.findByText("The microphone is in use by the camera or screen session.")).toBeInTheDocument();
  });
});
