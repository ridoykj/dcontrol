import { z } from "zod";

export const QUALITIES = ["LOW", "MEDIUM", "HIGH"] as const;
export type Quality = (typeof QUALITIES)[number];

export const qualityLabels: Record<Quality, string> = {
  LOW: "Low (720p, 15 fps)",
  MEDIUM: "Medium (1280p, 30 fps)",
  HIGH: "High (1920p, 30 fps)",
};

const iceServerSchema = z.object({
  urls: z.array(z.string()).min(1),
  username: z.string().nullable().optional(),
  credential: z.string().nullable().optional(),
});

export const startResultSchema = z.object({
  sessionId: z.string(),
  iceServers: z.array(iceServerSchema),
  audio: z.boolean(),
  control: z.boolean(),
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

export const screenStateSchema = z.object({
  deviceId: z.string(),
  sessionId: z.string(),
  state: z.enum(["AWAITING_CONSENT", "STARTING", "LIVE", "ENDED"]),
  reason: z.enum(END_REASONS).nullable(),
  control: z.boolean(),
});
export type ScreenStateEvent = z.infer<typeof screenStateSchema>;

export const offerSchema = z.object({
  deviceId: z.string(),
  sessionId: z.string(),
  kind: z.literal("OFFER"),
  sdp: z.string().min(1),
});

/** Ends the browser itself detects, besides the phone's and the controller's reasons. */
export type LocalEnd = "CONNECTION_FAILED" | "DISCONNECTED";

export type Phase = "idle" | "requesting" | "awaiting_consent" | "connecting" | "live" | "ended";

export type ScreenState = {
  phase: Phase;
  sessionId: string | null;
  /** What the phone said it can provide for this session. */
  audio: boolean;
  control: boolean;
  /** Whether input works right now (the Accessibility service may be turned off mid-session). */
  controlAvailable: boolean;
  endReason: EndReason | LocalEnd | null;
  error: string | null;
};

export const initialScreenState: ScreenState = {
  phase: "idle",
  sessionId: null,
  audio: false,
  control: false,
  controlAvailable: false,
  endReason: null,
  error: null,
};

export type ScreenAction =
  | { type: "request" }
  | { type: "started"; result: StartResult }
  | { type: "failed"; message: string }
  | { type: "phoneState"; event: ScreenStateEvent }
  | { type: "connection"; state: RTCPeerConnectionState }
  | { type: "controlAvailable"; available: boolean }
  | { type: "disconnected" }
  | { type: "stop" };

const active = (phase: Phase) => phase === "awaiting_consent" || phase === "connecting" || phase === "live";

export function screenReducer(state: ScreenState, action: ScreenAction): ScreenState {
  switch (action.type) {
    case "request":
      return { ...initialScreenState, phase: "requesting" };
    case "started":
      if (state.phase !== "requesting") return state;
      return {
        ...state,
        phase: "awaiting_consent",
        sessionId: action.result.sessionId,
        audio: action.result.audio,
        control: action.result.control,
        controlAvailable: action.result.control,
      };
    case "failed":
      return { ...state, phase: "ended", error: action.message };
    case "phoneState": {
      const event = action.event;
      if (event.sessionId !== state.sessionId || state.phase === "ended") return state;
      const controlAvailable = state.control && event.control;
      switch (event.state) {
        case "AWAITING_CONSENT":
          return { ...state, phase: "awaiting_consent", controlAvailable };
        case "STARTING":
          return { ...state, phase: state.phase === "live" ? "live" : "connecting", controlAvailable };
        case "LIVE":
          return { ...state, phase: "live", controlAvailable };
        case "ENDED":
          return { ...state, phase: "ended", controlAvailable: false, endReason: event.reason ?? "ERROR" };
      }
      return state;
    }
    case "connection":
      if (!active(state.phase)) return state;
      if (action.state === "connected") return { ...state, phase: "live" };
      if (action.state === "failed") {
        return { ...state, phase: "ended", controlAvailable: false, endReason: "CONNECTION_FAILED" };
      }
      return state;
    case "controlAvailable":
      return { ...state, controlAvailable: state.control && action.available };
    case "disconnected":
      if (!active(state.phase) && state.phase !== "requesting") return state;
      return { ...state, phase: "ended", controlAvailable: false, endReason: "DISCONNECTED" };
    case "stop":
      if (state.phase === "idle" || state.phase === "ended") return state;
      return { ...state, phase: "ended", controlAvailable: false, endReason: "STOPPED" };
  }
}

const endTexts: Record<EndReason | LocalEnd, string> = {
  DENIED: "Screen sharing was declined on the phone.",
  STOPPED_ON_PHONE: "Screen sharing was stopped on the phone.",
  STOPPED: "Screen sharing stopped.",
  ERROR: "The phone could not share its screen.",
  TIMEOUT: "Nobody tapped Start on the phone in time.",
  DEVICE_OFFLINE: "The phone went offline.",
  REPLACED: "Screen sharing was started from another browser tab.",
  CONNECTION_FAILED: "Could not connect to the phone. A firewall may block the connection, or no TURN relay is reachable.",
  DISCONNECTED: "The connection to the server dropped, so screen sharing stopped.",
};

export function endText(reason: EndReason | LocalEnd): string {
  return endTexts[reason];
}
