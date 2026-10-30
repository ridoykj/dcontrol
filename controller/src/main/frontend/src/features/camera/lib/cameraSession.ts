import { z } from "zod";

export const FACINGS = ["FRONT", "BACK"] as const;
export type Facing = (typeof FACINGS)[number];

export const facingLabels: Record<Facing, string> = {
  FRONT: "Front (selfie)",
  BACK: "Back",
};

/** Clip lengths offered for Record clip; the controller accepts 1 to 60 s. */
export const CLIP_SECONDS = [5, 10, 30, 60] as const;

const iceServerSchema = z.object({
  urls: z.array(z.string()).min(1),
  username: z.string().nullable().optional(),
  credential: z.string().nullable().optional(),
});

export const startResultSchema = z.object({
  sessionId: z.string(),
  iceServers: z.array(iceServerSchema),
  audio: z.boolean(),
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

export const cameraStateSchema = z.object({
  deviceId: z.string(),
  sessionId: z.string(),
  state: z.enum(["AWAITING_CONSENT", "STARTING", "LIVE", "ENDED"]),
  reason: z.enum(END_REASONS).nullable(),
  facing: z.enum(FACINGS).nullable(),
  video: z.boolean(),
});
export type CameraStateEvent = z.infer<typeof cameraStateSchema>;

export const offerSchema = z.object({
  deviceId: z.string(),
  sessionId: z.string(),
  kind: z.literal("OFFER"),
  sdp: z.string().min(1),
});

export const switchResultSchema = z.object({ facing: z.enum(FACINGS) });

export const captureSchema = z.object({
  mediaId: z.string(),
  kind: z.enum(["PHOTO", "VIDEO"]),
  mime: z.enum(["image/jpeg", "video/mp4"]),
  bytes: z.number(),
  width: z.number(),
  height: z.number(),
  durationMs: z.number().nullable(),
  capturedAt: z.string(),
});
export type Capture = z.infer<typeof captureSchema>;
export type CaptureKind = Capture["kind"];

/** Ends the browser itself detects, besides the phone's and the controller's reasons. */
export type LocalEnd = "CONNECTION_FAILED" | "DISCONNECTED";

export type Phase = "idle" | "requesting" | "awaiting_consent" | "connecting" | "live" | "ended";

export type CameraState = {
  phase: Phase;
  sessionId: string | null;
  /** What the phone said it can provide for this session. */
  audio: boolean;
  /** The camera in use: the one asked for until the phone reports it. */
  facing: Facing | null;
  /** Whether clips can be recorded; false means photos only. */
  video: boolean;
  endReason: EndReason | LocalEnd | null;
  error: string | null;
};

export const initialCameraState: CameraState = {
  phase: "idle",
  sessionId: null,
  audio: false,
  facing: null,
  video: false,
  endReason: null,
  error: null,
};

export type CameraAction =
  | { type: "request"; facing: Facing }
  | { type: "started"; result: StartResult }
  | { type: "failed"; message: string }
  | { type: "phoneState"; event: CameraStateEvent }
  | { type: "connection"; state: RTCPeerConnectionState }
  | { type: "switched"; facing: Facing }
  | { type: "disconnected" }
  | { type: "stop" };

const active = (phase: Phase) => phase === "awaiting_consent" || phase === "connecting" || phase === "live";

export function cameraReducer(state: CameraState, action: CameraAction): CameraState {
  switch (action.type) {
    case "request":
      return { ...initialCameraState, phase: "requesting", facing: action.facing };
    case "started":
      if (state.phase !== "requesting") return state;
      return { ...state, phase: "awaiting_consent", sessionId: action.result.sessionId, audio: action.result.audio };
    case "failed":
      return { ...state, phase: "ended", error: action.message };
    case "phoneState": {
      const event = action.event;
      if (event.sessionId !== state.sessionId || state.phase === "ended") return state;
      if (event.state === "ENDED") return { ...state, phase: "ended", endReason: event.reason ?? "ERROR" };
      const reported = { ...state, facing: event.facing ?? state.facing, video: event.video };
      switch (event.state) {
        case "AWAITING_CONSENT":
          return { ...reported, phase: "awaiting_consent" };
        case "STARTING":
          return { ...reported, phase: state.phase === "live" ? "live" : "connecting" };
        case "LIVE":
          return { ...reported, phase: "live" };
      }
      return state;
    }
    case "connection":
      if (!active(state.phase)) return state;
      if (action.state === "connected") return { ...state, phase: "live" };
      if (action.state === "failed") return { ...state, phase: "ended", endReason: "CONNECTION_FAILED" };
      return state;
    case "switched":
      if (!active(state.phase)) return state;
      return { ...state, facing: action.facing };
    case "disconnected":
      if (!active(state.phase) && state.phase !== "requesting") return state;
      return { ...state, phase: "ended", endReason: "DISCONNECTED" };
    case "stop":
      if (state.phase === "idle" || state.phase === "ended") return state;
      return { ...state, phase: "ended", endReason: "STOPPED" };
  }
}

const endTexts: Record<EndReason | LocalEnd, string> = {
  DENIED: "Camera access was declined on the phone.",
  STOPPED_ON_PHONE: "The camera was stopped on the phone.",
  STOPPED: "The camera stopped.",
  ERROR: "The phone could not share its camera.",
  TIMEOUT: "Nobody accepted on the phone in time.",
  DEVICE_OFFLINE: "The phone went offline.",
  REPLACED: "The camera was started from another browser tab.",
  CONNECTION_FAILED: "Could not connect to the phone. A firewall may block the connection, or no TURN relay is reachable.",
  DISCONNECTED: "The connection to the server dropped, so the camera stopped.",
};

export function endText(reason: EndReason | LocalEnd): string {
  return endTexts[reason];
}

/** A file name such as {@code camera-20261030-101530.jpg}, from the capture time in local time. */
export function captureFileName(capture: Pick<Capture, "capturedAt" | "mime">): string {
  const at = new Date(capture.capturedAt);
  const pad = (value: number) => String(value).padStart(2, "0");
  const stamp = Number.isNaN(at.getTime())
    ? "capture"
    : `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
  return `camera-${stamp}.${capture.mime === "video/mp4" ? "mp4" : "jpg"}`;
}
