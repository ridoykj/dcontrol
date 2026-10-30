import { z } from "zod";

/** Clip lengths offered for Record clip; the controller accepts 1 s to 5 min. */
export const CLIP_SECONDS = [10, 30, 60, 300] as const;

const iceServerSchema = z.object({
  urls: z.array(z.string()).min(1),
  username: z.string().nullable().optional(),
  credential: z.string().nullable().optional(),
});

export const startResultSchema = z.object({
  sessionId: z.string(),
  iceServers: z.array(iceServerSchema),
});
export type StartResult = z.infer<typeof startResultSchema>;

export const END_REASONS = [
  "DENIED",
  "STOPPED_ON_PHONE",
  "STOPPED",
  "ERROR",
  "TIMEOUT",
  "DEVICE_OFFLINE",
  "REPLACED",
] as const;
export type EndReason = (typeof END_REASONS)[number];

export const micStateSchema = z.object({
  deviceId: z.string(),
  sessionId: z.string(),
  state: z.enum(["AWAITING_CONSENT", "STARTING", "LIVE", "ENDED"]),
  reason: z.enum(END_REASONS).nullable(),
});
export type MicStateEvent = z.infer<typeof micStateSchema>;

export const offerSchema = z.object({
  deviceId: z.string(),
  sessionId: z.string(),
  kind: z.literal("OFFER"),
  sdp: z.string().min(1),
});

export const captureSchema = z.object({
  mediaId: z.string(),
  mime: z.literal("audio/mp4"),
  bytes: z.number(),
  durationMs: z.number().nullable(),
  capturedAt: z.string(),
});
export type Capture = z.infer<typeof captureSchema>;

/** Ends the browser itself detects, besides the phone's and the controller's reasons. */
export type LocalEnd = "CONNECTION_FAILED" | "DISCONNECTED";

export type Phase = "idle" | "requesting" | "awaiting_consent" | "connecting" | "live" | "ended";

export type MicState = {
  phase: Phase;
  sessionId: string | null;
  endReason: EndReason | LocalEnd | null;
  error: string | null;
};

export const initialMicState: MicState = {
  phase: "idle",
  sessionId: null,
  endReason: null,
  error: null,
};

export type MicAction =
  | { type: "request" }
  | { type: "started"; result: StartResult }
  | { type: "failed"; message: string }
  | { type: "phoneState"; event: MicStateEvent }
  | { type: "connection"; state: RTCPeerConnectionState }
  | { type: "disconnected" }
  | { type: "stop" };

const active = (phase: Phase) => phase === "awaiting_consent" || phase === "connecting" || phase === "live";

export function micReducer(state: MicState, action: MicAction): MicState {
  switch (action.type) {
    case "request":
      return { ...initialMicState, phase: "requesting" };
    case "started":
      if (state.phase !== "requesting") return state;
      return { ...state, phase: "awaiting_consent", sessionId: action.result.sessionId };
    case "failed":
      return { ...state, phase: "ended", error: action.message };
    case "phoneState": {
      const event = action.event;
      if (event.sessionId !== state.sessionId || state.phase === "ended") return state;
      if (event.state === "ENDED") return { ...state, phase: "ended", endReason: event.reason ?? "ERROR" };
      switch (event.state) {
        case "AWAITING_CONSENT":
          return { ...state, phase: "awaiting_consent" };
        case "STARTING":
          return { ...state, phase: state.phase === "live" ? "live" : "connecting" };
        case "LIVE":
          return { ...state, phase: "live" };
      }
      return state;
    }
    case "connection":
      if (!active(state.phase)) return state;
      if (action.state === "connected") return { ...state, phase: "live" };
      if (action.state === "failed") return { ...state, phase: "ended", endReason: "CONNECTION_FAILED" };
      return state;
    case "disconnected":
      if (!active(state.phase) && state.phase !== "requesting") return state;
      return { ...state, phase: "ended", endReason: "DISCONNECTED" };
    case "stop":
      if (state.phase === "idle" || state.phase === "ended") return state;
      return { ...state, phase: "ended", endReason: "STOPPED" };
  }
}

const endTexts: Record<EndReason | LocalEnd, string> = {
  DENIED: "Microphone access was declined on the phone.",
  STOPPED_ON_PHONE: "The microphone was stopped on the phone.",
  STOPPED: "The microphone stopped.",
  ERROR: "The phone could not share its microphone.",
  TIMEOUT: "Nobody accepted on the phone in time.",
  DEVICE_OFFLINE: "The phone went offline.",
  REPLACED: "The microphone was started from another browser tab.",
  CONNECTION_FAILED:
    "Could not connect to the phone. A firewall may block the connection, or no TURN relay is reachable.",
  DISCONNECTED: "The connection to the server dropped, so the microphone stopped.",
};

export function endText(reason: EndReason | LocalEnd): string {
  return endTexts[reason];
}

/** A file name such as {@code mic-20261030-101530.m4a}, from the capture time in local time. */
export function captureFileName(capture: Pick<Capture, "capturedAt">): string {
  const at = new Date(capture.capturedAt);
  const pad = (value: number) => String(value).padStart(2, "0");
  const stamp = Number.isNaN(at.getTime())
    ? "capture"
    : `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
  return `mic-${stamp}.m4a`;
}
