import { describe, expect, it } from "vitest";
import {
  endText,
  initialScreenState,
  type ScreenAction,
  screenReducer,
  type ScreenState,
  type ScreenStateEvent,
} from "./screenSession";

const result = { sessionId: "s1", iceServers: [{ urls: ["stun:localhost:3478"] }], audio: true, control: true };

function run(...actions: ScreenAction[]): ScreenState {
  return actions.reduce(screenReducer, initialScreenState);
}

function phone(state: ScreenStateEvent["state"], extra: Partial<ScreenStateEvent> = {}): ScreenAction {
  return {
    type: "phoneState",
    event: { deviceId: "d1", sessionId: "s1", state, reason: null, control: true, ...extra },
  };
}

describe("screenReducer", () => {
  it("walks through consent, connection and live", () => {
    const requested = run({ type: "request" });
    expect(requested.phase).toBe("requesting");
    const started = screenReducer(requested, { type: "started", result });
    expect(started).toMatchObject({ phase: "awaiting_consent", sessionId: "s1", audio: true, controlAvailable: true });
    expect(screenReducer(started, phone("STARTING")).phase).toBe("connecting");
    expect(screenReducer(started, phone("LIVE")).phase).toBe("live");
    expect(run({ type: "request" }, { type: "started", result }, phone("STARTING"), { type: "connection", state: "connected" }).phase).toBe("live");
  });

  it("ignores states of other sessions and results that arrive after a stop", () => {
    const started = run({ type: "request" }, { type: "started", result });
    expect(screenReducer(started, phone("ENDED", { sessionId: "old", reason: "REPLACED" }))).toBe(started);
    const stopped = run({ type: "request" }, { type: "stop" });
    expect(screenReducer(stopped, { type: "started", result })).toBe(stopped);
  });

  it("ends with the phone's or the controller's reason", () => {
    const ended = run({ type: "request" }, { type: "started", result }, phone("ENDED", { reason: "DENIED" }));
    expect(ended).toMatchObject({ phase: "ended", endReason: "DENIED", controlAvailable: false });
    expect(screenReducer(ended, phone("LIVE"))).toBe(ended);
    expect(run({ type: "request" }, { type: "started", result }, phone("ENDED", { reason: "TIMEOUT" })).endReason).toBe("TIMEOUT");
  });

  it("ends on a failed connection, a dropped server socket, a failed start or a stop", () => {
    const live = run({ type: "request" }, { type: "started", result }, phone("LIVE"));
    expect(screenReducer(live, { type: "connection", state: "failed" }).endReason).toBe("CONNECTION_FAILED");
    expect(screenReducer(live, { type: "disconnected" }).endReason).toBe("DISCONNECTED");
    expect(screenReducer(live, { type: "stop" }).endReason).toBe("STOPPED");
    expect(run({ type: "request" }, { type: "failed", message: "The phone is offline." })).toMatchObject({
      phase: "ended",
      error: "The phone is offline.",
    });
    expect(screenReducer(initialScreenState, { type: "disconnected" })).toBe(initialScreenState);
  });

  it("tracks whether input works right now", () => {
    const live = run({ type: "request" }, { type: "started", result }, phone("LIVE"));
    expect(screenReducer(live, { type: "controlAvailable", available: false }).controlAvailable).toBe(false);
    expect(screenReducer(live, phone("LIVE", { control: false })).controlAvailable).toBe(false);
    const viewOnly = run({ type: "request" }, { type: "started", result: { ...result, control: false } }, phone("LIVE"));
    expect(screenReducer(viewOnly, { type: "controlAvailable", available: true }).controlAvailable).toBe(false);
  });

  it("explains every end", () => {
    expect(endText("TIMEOUT")).toMatch(/tapped Start/);
    expect(endText("CONNECTION_FAILED")).toMatch(/TURN/);
  });
});
