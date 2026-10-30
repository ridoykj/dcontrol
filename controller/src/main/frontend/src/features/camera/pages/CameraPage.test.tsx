import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deviceKeys } from "@/features/devices/api/devicesApi";
import type { Device } from "@/features/devices/schemas/deviceSchemas";
import { api } from "@/shared/api/client";
import { type Envelope, RealtimeError, type RealtimeClient } from "@/shared/ws/client";
import { RealtimeClientProvider } from "@/shared/ws/RealtimeProvider";
import { CameraPage } from "./CameraPage";

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

const startResult = { sessionId: "s1", iceServers: [{ urls: ["stun:localhost:3478"], username: null }], audio: false };

const photo = {
  mediaId: "m1",
  kind: "PHOTO",
  mime: "image/jpeg",
  bytes: 2048,
  width: 4000,
  height: 3000,
  durationMs: null,
  capturedAt: "2026-10-30T10:15:30Z",
};

function renderPage(handler: Handler) {
  const fake = fakeClient(handler);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  queryClient.setQueryData(deviceKeys.detail("d1"), { id: "d1", status: { online: true } } as unknown as Device);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RealtimeClientProvider client={fake.client as unknown as RealtimeClient}>
        <CameraPage deviceId="d1" />
      </RealtimeClientProvider>
    </QueryClientProvider>,
  );
  return { ...fake, view };
}

function state(value: string, extra: Record<string, unknown> = {}) {
  return { deviceId: "d1", sessionId: "s1", state: value, reason: null, facing: "BACK", video: true, ...extra };
}

describe("CameraPage", () => {
  beforeEach(() => {
    FakePeerConnection.instances = [];
    vi.stubGlobal("RTCPeerConnection", FakePeerConnection);
    vi.stubGlobal(
      "MediaStream",
      class {
        addTrack() {}
      },
    );
    URL.createObjectURL = vi.fn(() => "blob:capture");
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(api, "requestBlob").mockResolvedValue(new Blob(["jpeg"], { type: "image/jpeg" }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("starts a session, answers the phone's offer, takes a photo and switches cameras", async () => {
    const { client, push, view } = renderPage(async (type, payload) => {
      if (type === "camera.start") return startResult;
      if (type === "camera.capture") return photo;
      if (type === "camera.switch") return { facing: (payload as { facing: string }).facing };
      throw new Error(type);
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request camera" }));
    expect(client.request).toHaveBeenCalledWith("camera.start", { deviceId: "d1", facing: "BACK", audio: false });

    push("camera.state", state("AWAITING_CONSENT"));
    expect(await screen.findByText(/Waiting for someone to accept/)).toBeInTheDocument();

    push("camera.signal", { deviceId: "other", sessionId: "s1", kind: "OFFER", sdp: "not-mine" });
    push("camera.signal", { deviceId: "d1", sessionId: "old", kind: "OFFER", sdp: "stale" });
    push("camera.signal", { deviceId: "d1", sessionId: "s1", kind: "OFFER", sdp: "offer-sdp" });
    await waitFor(() =>
      expect(client.send).toHaveBeenCalledWith("camera.signal", { sessionId: "s1", kind: "ANSWER", sdp: "answer-sdp" }),
    );
    expect(FakePeerConnection.instances).toHaveLength(1);
    expect(FakePeerConnection.instances[0]!.remoteDescription).toEqual({ type: "offer", sdp: "offer-sdp" });
    expect(FakePeerConnection.instances[0]!.config.iceServers).toEqual([{ urls: ["stun:localhost:3478"] }]);

    push("camera.state", state("LIVE"));
    await user.click(screen.getByRole("button", { name: "Take photo" }));
    expect(client.request).toHaveBeenCalledWith(
      "camera.capture",
      { sessionId: "s1", kind: "PHOTO", durationMs: null },
      130_000,
    );
    const strip = await screen.findByRole("list", { name: "Captures" });
    expect(await within(strip).findByRole("img", { name: /^Photo, 4000×3000/ })).toHaveAttribute("src", "blob:capture");
    expect(api.requestBlob).toHaveBeenCalledWith("/api/devices/d1/camera/media/m1");
    await waitFor(() => expect(within(strip).getByRole("button", { name: "Download" })).toBeEnabled());

    await user.click(screen.getByRole("button", { name: "Switch camera" }));
    expect(client.request).toHaveBeenCalledWith("camera.switch", { sessionId: "s1", facing: "FRONT" });
    await waitFor(() => expect(screen.getByRole("button", { name: "Switch camera" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Switch camera" }));
    expect(client.request).toHaveBeenLastCalledWith("camera.switch", { sessionId: "s1", facing: "BACK" });

    view.unmount();
    expect(client.send).toHaveBeenCalledWith("camera.stop", { sessionId: "s1" });
  });

  it("records a clip of the chosen length and blocks switching meanwhile", async () => {
    let finish: (value: unknown) => void = () => {};
    const { client, push } = renderPage(async (type) => {
      if (type === "camera.start") return startResult;
      if (type === "camera.capture") return new Promise((resolve) => (finish = resolve));
      throw new Error(type);
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request camera" }));
    await waitFor(() => expect(client.request).toHaveBeenCalledTimes(1));
    push("camera.state", state("LIVE"));

    await user.click(screen.getByRole("button", { name: /Clip length/ }));
    await user.click(await screen.findByRole("option", { name: "30 s" }));
    await user.click(screen.getByRole("button", { name: "Record clip" }));
    expect(client.request).toHaveBeenCalledWith(
      "camera.capture",
      { sessionId: "s1", kind: "VIDEO", durationMs: 30_000 },
      130_000,
    );
    expect(screen.getByRole("button", { name: "Recording…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Switch camera" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Take photo" })).toBeDisabled();

    await act(async () =>
      finish({ ...photo, mediaId: "m2", kind: "VIDEO", mime: "video/mp4", durationMs: 30_000 }),
    );
    expect(await screen.findByLabelText(/^Clip, 30 s/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Switch camera" })).toBeEnabled();
  });

  it("offers photos only when the phone can't record clips", async () => {
    const { client, push } = renderPage(async () => startResult);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request camera" }));
    await waitFor(() => expect(client.request).toHaveBeenCalledTimes(1));
    push("camera.state", state("LIVE", { video: false }));
    expect(screen.getByRole("button", { name: "Take photo" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Record clip" })).toBeNull();
    expect(screen.getByText(/photos only/)).toBeInTheDocument();

    push("camera.state", state("ENDED", { reason: "STOPPED_ON_PHONE", facing: null, video: false }));
    expect(await screen.findByText("The camera was stopped on the phone.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Request camera" })).toBeInTheDocument();
  });

  it("points at the Camera card when the phone lacks the camera permission", async () => {
    renderPage(async () => {
      throw new RealtimeError("PERMISSION_REQUIRED", "Camera permission missing", "CAMERA");
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Request camera" }));
    expect(await screen.findByText(/hasn't allowed dcontrol to use the camera/)).toBeInTheDocument();
    expect(screen.queryByText(/hasn't granted the permission this needs/)).toBeNull();
  });
});
