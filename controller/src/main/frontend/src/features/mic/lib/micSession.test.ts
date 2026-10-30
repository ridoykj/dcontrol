import { describe, expect, it } from "vitest";
import {
  captureFileName,
  captureSchema,
  endText,
  initialMicState,
  type MicAction,
  micReducer,
  type MicState,
  type MicStateEvent,
} from "./micSession";

const result = { sessionId: "s1", iceServers: [{ urls: ["stun:localhost:3478"] }] };

function run(...actions: MicAction[]): MicState {
  return actions.reduce(micReducer, initialMicState);
}

function phone(state: MicStateEvent["state"], extra: Partial<MicStateEvent> = {}): MicAction {
  return {
    type: "phoneState",
    event: { deviceId: "d1", sessionId: "s1", state, reason: null, ...extra },
  };
}

const request: MicAction = { type: "request" };

describe("micReducer", () => {
  it("walks through consent, connection and live", () => {
    const requested = run(request);
    expect(requested).toMatchObject({ phase: "requesting" });
    const started = micReducer(requested, { type: "started", result });
    expect(started).toMatchObject({ phase: "awaiting_consent", sessionId: "s1" });
    expect(micReducer(started, phone("STARTING")).phase).toBe("connecting");
    expect(micReducer(started, phone("LIVE")).phase).toBe("live");
    expect(run(request, { type: "started", result }, phone("STARTING"), { type: "connection", state: "connected" }).phase).toBe(
      "live",
    );
  });

  it("keeps STARTING as live once the stream is up", () => {
    const live = run(request, { type: "started", result }, phone("LIVE"));
    expect(micReducer(live, phone("STARTING")).phase).toBe("live");
  });

  it("ends with the phone's reason, falling back to ERROR", () => {
    const live = run(request, { type: "started", result }, phone("LIVE"));
    expect(micReducer(live, phone("ENDED", { reason: "DEVICE_OFFLINE" }))).toMatchObject({
      phase: "ended",
      endReason: "DEVICE_OFFLINE",
    });
    expect(micReducer(live, phone("ENDED")).endReason).toBe("ERROR");
  });

  it("ignores states of other sessions and results that arrive after a stop", () => {
    const started = run(request, { type: "started", result });
    expect(micReducer(started, phone("ENDED", { sessionId: "old", reason: "REPLACED" }))).toBe(started);
    const stopped = run(request, { type: "stop" });
    expect(micReducer(stopped, { type: "started", result })).toBe(stopped);
    expect(micReducer(stopped, phone("LIVE"))).toBe(stopped);
  });

  it("ends on failures the browser sees itself", () => {
    const started = run(request, { type: "started", result });
    expect(micReducer(started, { type: "connection", state: "failed" })).toMatchObject({
      phase: "ended",
      endReason: "CONNECTION_FAILED",
    });
    expect(micReducer(started, { type: "disconnected" }).endReason).toBe("DISCONNECTED");
    expect(run(request, { type: "failed", message: "nope" })).toMatchObject({ phase: "ended", error: "nope" });
    expect(micReducer(initialMicState, { type: "disconnected" })).toBe(initialMicState);
  });

  it("has a text for every end reason", () => {
    expect(endText("DENIED")).toMatch(/declined/);
    expect(endText("TIMEOUT")).toMatch(/in time/);
    expect(endText("REPLACED")).toMatch(/another browser tab/);
  });
});

describe("captureSchema and captureFileName", () => {
  it("parses a clip result and names it from the capture time", () => {
    const clip = captureSchema.parse({
      mediaId: "m1",
      mime: "audio/mp4",
      bytes: 4096,
      durationMs: 30_000,
      capturedAt: "2026-10-30T10:15:30Z",
    });
    expect(clip.mime).toBe("audio/mp4");
    expect(captureFileName(clip)).toMatch(/^mic-\d{8}-\d{6}\.m4a$/);
    expect(captureFileName({ capturedAt: "not-a-date" })).toBe("mic-capture.m4a");
  });
});
