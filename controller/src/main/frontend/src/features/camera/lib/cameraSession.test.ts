import { describe, expect, it } from "vitest";
import {
  type CameraAction,
  cameraReducer,
  type CameraState,
  type CameraStateEvent,
  captureFileName,
  captureSchema,
  endText,
  initialCameraState,
} from "./cameraSession";

const result = { sessionId: "s1", iceServers: [{ urls: ["stun:localhost:3478"] }], audio: true };

function run(...actions: CameraAction[]): CameraState {
  return actions.reduce(cameraReducer, initialCameraState);
}

function phone(state: CameraStateEvent["state"], extra: Partial<CameraStateEvent> = {}): CameraAction {
  return {
    type: "phoneState",
    event: { deviceId: "d1", sessionId: "s1", state, reason: null, facing: "BACK", video: true, ...extra },
  };
}

const request: CameraAction = { type: "request", facing: "BACK" };

describe("cameraReducer", () => {
  it("walks through consent, connection and live, keeping what the phone reports", () => {
    const requested = run(request);
    expect(requested).toMatchObject({ phase: "requesting", facing: "BACK", video: false });
    const started = cameraReducer(requested, { type: "started", result });
    expect(started).toMatchObject({ phase: "awaiting_consent", sessionId: "s1", audio: true });
    expect(cameraReducer(started, phone("STARTING")).phase).toBe("connecting");
    const live = cameraReducer(started, phone("LIVE", { facing: "FRONT", video: false }));
    expect(live).toMatchObject({ phase: "live", facing: "FRONT", video: false });
    expect(run(request, { type: "started", result }, phone("STARTING"), { type: "connection", state: "connected" }).phase).toBe(
      "live",
    );
  });

  it("keeps the camera in use when the controller ends the session without one", () => {
    const live = run(request, { type: "started", result }, phone("LIVE", { facing: "FRONT" }));
    const ended = cameraReducer(live, phone("ENDED", { reason: "DEVICE_OFFLINE", facing: null, video: false }));
    expect(ended).toMatchObject({ phase: "ended", endReason: "DEVICE_OFFLINE", facing: "FRONT" });
    expect(cameraReducer(live, phone("ENDED")).endReason).toBe("ERROR");
  });

  it("ignores states of other sessions and results that arrive after a stop", () => {
    const started = run(request, { type: "started", result });
    expect(cameraReducer(started, phone("ENDED", { sessionId: "old", reason: "REPLACED" }))).toBe(started);
    const stopped = run(request, { type: "stop" });
    expect(cameraReducer(stopped, { type: "started", result })).toBe(stopped);
    expect(cameraReducer(stopped, phone("LIVE"))).toBe(stopped);
  });

  it("records a camera switch only while the session runs", () => {
    const live = run(request, { type: "started", result }, phone("LIVE"));
    expect(cameraReducer(live, { type: "switched", facing: "FRONT" }).facing).toBe("FRONT");
    const ended = cameraReducer(live, { type: "stop" });
    expect(cameraReducer(ended, { type: "switched", facing: "FRONT" })).toBe(ended);
  });

  it("ends on failures the browser sees itself", () => {
    const started = run(request, { type: "started", result });
    expect(cameraReducer(started, { type: "connection", state: "failed" })).toMatchObject({
      phase: "ended",
      endReason: "CONNECTION_FAILED",
    });
    expect(cameraReducer(started, { type: "disconnected" }).endReason).toBe("DISCONNECTED");
    expect(run(request, { type: "failed", message: "nope" })).toMatchObject({ phase: "ended", error: "nope" });
    expect(cameraReducer(initialCameraState, { type: "disconnected" })).toBe(initialCameraState);
  });

  it("has a text for every end reason", () => {
    expect(endText("DENIED")).toMatch(/declined/);
    expect(endText("TIMEOUT")).toMatch(/in time/);
  });
});

describe("captures", () => {
  it("parses the capture result and names the download after the capture time", () => {
    const capture = captureSchema.parse({
      mediaId: "m1",
      kind: "VIDEO",
      mime: "video/mp4",
      bytes: 1024,
      width: 1280,
      height: 720,
      durationMs: 10_000,
      capturedAt: new Date(2026, 9, 30, 10, 15, 30).toISOString(),
    });
    expect(captureFileName(capture)).toBe("camera-20261030-101530.mp4");
    expect(captureFileName({ capturedAt: "garbage", mime: "image/jpeg" })).toBe("camera-capture.jpg");
    expect(captureSchema.safeParse({ ...capture, mime: "text/html" }).success).toBe(false);
  });
});
