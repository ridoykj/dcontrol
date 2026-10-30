import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { gatheringComplete, noStats, readStats, type RtcStats, toRtcIceServers } from "@/shared/rtc/peer";
import { type Envelope, RealtimeError } from "@/shared/ws/client";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { captureMedia, switchCamera as requestSwitch } from "../api/cameraApi";
import {
  cameraReducer,
  cameraStateSchema,
  type Capture,
  type CaptureKind,
  type Facing,
  initialCameraState,
  offerSchema,
  startResultSchema,
} from "../lib/cameraSession";

const STATS_INTERVAL_MS = 2000;
/** The controller keeps at most 20 captures per phone; older ones are gone anyway. */
const MAX_CAPTURES = 20;

export type CameraStartOptions = { facing: Facing; audio: boolean };

const actionErrors: Record<string, string> = {
  CONFLICT: "The camera is busy: wait for the running capture to finish.",
  UNSUPPORTED: "The phone can't do that with this camera.",
  NOT_FOUND: "The camera session has ended.",
};

function actionErrorMessage(error: unknown): string {
  return (error instanceof RealtimeError && actionErrors[error.code]) || relayErrorMessage(error);
}

/**
 * One camera session with a phone: asks the controller to start it, answers the phone's WebRTC offer and keeps
 * the media stream. Photos and clips go through the controller. Leaving the page stops the session.
 */
export function useCameraSession(deviceId: string) {
  const { client } = useRealtime();
  const [state, dispatch] = useReducer(cameraReducer, initialCameraState);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [stats, setStats] = useState<RtcStats>(noStats);
  /** Why camera.start failed, kept whole so the page can tell a missing permission apart. */
  const [failure, setFailure] = useState<unknown>(null);
  const [captures, setCaptures] = useState<Capture[]>([]);
  const [capturing, setCapturing] = useState<CaptureKind | null>(null);
  const [switching, setSwitching] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const session = useRef<{ id: string; iceServers: RTCIceServer[] } | null>(null);
  const requesting = useRef(false);
  const early = useRef<Envelope[]>([]);
  const pc = useRef<RTCPeerConnection | null>(null);

  const teardown = useCallback(() => {
    pc.current?.close();
    pc.current = null;
    session.current = null;
    setStream(null);
    setStats(noStats);
  }, []);

  const answerOffer = useCallback(
    async (sdp: string) => {
      const current = session.current;
      if (!current || pc.current) return;
      const connection = new RTCPeerConnection({ iceServers: current.iceServers });
      pc.current = connection;
      const media = new MediaStream();
      connection.ontrack = (event) => {
        media.addTrack(event.track);
        setStream(media);
      };
      connection.onconnectionstatechange = () => {
        if (pc.current === connection) dispatch({ type: "connection", state: connection.connectionState });
      };
      try {
        await connection.setRemoteDescription({ type: "offer", sdp });
        await connection.setLocalDescription(await connection.createAnswer());
        await gatheringComplete(connection);
        if (pc.current !== connection || !connection.localDescription) return;
        client.send("camera.signal", { sessionId: current.id, kind: "ANSWER", sdp: connection.localDescription.sdp });
      } catch {
        if (pc.current !== connection) return;
        client.send("camera.stop", { sessionId: current.id });
        teardown();
        dispatch({ type: "connection", state: "failed" });
      }
    },
    [client, teardown],
  );

  const handle = useCallback(
    (message: Envelope) => {
      if (message.type === "camera.state") {
        const parsed = cameraStateSchema.safeParse(message.payload);
        if (!parsed.success || parsed.data.sessionId !== session.current?.id) return;
        dispatch({ type: "phoneState", event: parsed.data });
        if (parsed.data.state === "ENDED") teardown();
      } else if (message.type === "camera.signal") {
        const parsed = offerSchema.safeParse(message.payload);
        if (parsed.success && parsed.data.sessionId === session.current?.id) void answerOffer(parsed.data.sdp);
      }
    },
    [answerOffer, teardown],
  );

  useEffect(
    () =>
      client.subscribe((message) => {
        if (message.type !== "camera.state" && message.type !== "camera.signal") return;
        const payload = message.payload as { deviceId?: unknown } | undefined;
        if (payload?.deviceId !== deviceId) return;
        // The result should arrive first; keep anything that overtakes it until the session id is known.
        if (requesting.current && !session.current) early.current.push(message);
        else handle(message);
      }),
    [client, deviceId, handle],
  );

  useEffect(
    () =>
      client.onStateChange((connection) => {
        if (connection === "closed" && (session.current || requesting.current)) {
          requesting.current = false;
          teardown();
          dispatch({ type: "disconnected" });
        }
      }),
    [client, teardown],
  );

  const start = useCallback(
    (options: CameraStartOptions) => {
      if (session.current) client.send("camera.stop", { sessionId: session.current.id });
      teardown();
      early.current = [];
      requesting.current = true;
      setFailure(null);
      setActionError(null);
      dispatch({ type: "request", facing: options.facing });
      client
        .request("camera.start", { deviceId, ...options })
        .then((envelope) => {
          if (!requesting.current) return;
          const result = startResultSchema.parse(envelope.payload);
          requesting.current = false;
          session.current = { id: result.sessionId, iceServers: toRtcIceServers(result.iceServers) };
          dispatch({ type: "started", result });
          const queued = early.current;
          early.current = [];
          queued.forEach(handle);
        })
        .catch((error: unknown) => {
          if (!requesting.current) return;
          requesting.current = false;
          setFailure(error);
          dispatch({ type: "failed", message: relayErrorMessage(error) });
        });
    },
    [client, deviceId, handle, teardown],
  );

  const stop = useCallback(() => {
    requesting.current = false;
    if (session.current) client.send("camera.stop", { sessionId: session.current.id });
    teardown();
    dispatch({ type: "stop" });
  }, [client, teardown]);

  /** Takes a photo or records a clip of {@code durationMs}; the result joins the captures once uploaded. */
  const capture = useCallback(
    async (kind: CaptureKind, durationMs: number | null = null) => {
      const current = session.current;
      if (!current) return;
      setCapturing(kind);
      setActionError(null);
      try {
        const result = await captureMedia(client, current.id, kind, durationMs);
        setCaptures((list) => [result, ...list.filter((item) => item.mediaId !== result.mediaId)].slice(0, MAX_CAPTURES));
      } catch (error) {
        setActionError(actionErrorMessage(error));
      } finally {
        setCapturing(null);
      }
    },
    [client],
  );

  const switchCamera = useCallback(
    async (facing: Facing) => {
      const current = session.current;
      if (!current) return;
      setSwitching(true);
      setActionError(null);
      try {
        const now = await requestSwitch(client, current.id, facing);
        if (session.current?.id === current.id) dispatch({ type: "switched", facing: now });
      } catch (error) {
        setActionError(actionErrorMessage(error));
      } finally {
        setSwitching(false);
      }
    },
    [client],
  );

  useEffect(() => {
    if (state.phase !== "live") return;
    let previous: { bytes: number; at: number } | null = null;
    const timer = setInterval(() => {
      const connection = pc.current;
      if (!connection) return;
      void readStats(connection, previous).then(({ stats: next, sample }) => {
        previous = sample;
        setStats(next);
      });
    }, STATS_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [state.phase]);

  // Leaving the page (or switching tabs) ends the session on the phone too.
  useEffect(
    () => () => {
      requesting.current = false;
      if (session.current) client.send("camera.stop", { sessionId: session.current.id });
      teardown();
    },
    [client, teardown],
  );

  return {
    state,
    stream,
    stats,
    failure,
    captures,
    capturing,
    switching,
    actionError,
    start,
    stop,
    capture,
    switchCamera,
  };
}
