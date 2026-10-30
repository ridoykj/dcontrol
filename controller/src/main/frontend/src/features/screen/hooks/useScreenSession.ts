import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import type { Envelope } from "@/shared/ws/client";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { type ControlMessage, phoneMessageSchema } from "../lib/controlMessages";
import {
  initialScreenState,
  offerSchema,
  type Quality,
  screenReducer,
  screenStateSchema,
  startResultSchema,
} from "../lib/screenSession";

const GATHERING_TIMEOUT_MS = 4000;
const STATS_INTERVAL_MS = 2000;

export type StartOptions = { quality: Quality; audio: boolean; control: boolean };
export type ScreenStats = { fps: number | null; kbps: number | null; route: "direct" | "relay" | null };

/** Resolves once ICE gathering is complete (non-trickle signaling), or after 4 s with what was gathered. */
function gatheringComplete(pc: RTCPeerConnection): Promise<void> {
  if (pc.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, GATHERING_TIMEOUT_MS);
    function done() {
      clearTimeout(timer);
      pc.removeEventListener("icegatheringstatechange", check);
      resolve();
    }
    function check() {
      if (pc.iceGatheringState === "complete") done();
    }
    pc.addEventListener("icegatheringstatechange", check);
  });
}

async function readStats(pc: RTCPeerConnection, previous: { bytes: number; at: number } | null) {
  const report = await pc.getStats();
  let fps: number | null = null;
  let bytes = 0;
  let pairId: string | null = null;
  const candidates = new Map<string, string>();
  report.forEach((entry: Record<string, unknown>) => {
    if (entry.type === "inbound-rtp" && entry.kind === "video") {
      fps = typeof entry.framesPerSecond === "number" ? entry.framesPerSecond : fps;
    }
    if (entry.type === "inbound-rtp" && typeof entry.bytesReceived === "number") bytes += entry.bytesReceived;
    if (entry.type === "transport" && typeof entry.selectedCandidatePairId === "string") {
      pairId = entry.selectedCandidatePairId;
    }
    if (entry.type === "local-candidate" && typeof entry.id === "string" && typeof entry.candidateType === "string") {
      candidates.set(entry.id, entry.candidateType);
    }
  });
  let route: ScreenStats["route"] = null;
  if (pairId) {
    const pair = report.get(pairId) as { localCandidateId?: string } | undefined;
    const type = pair?.localCandidateId ? candidates.get(pair.localCandidateId) : undefined;
    if (type) route = type === "relay" ? "relay" : "direct";
  }
  const at = performance.now();
  const kbps = previous && at > previous.at ? Math.round(((bytes - previous.bytes) * 8) / (at - previous.at)) : null;
  return { stats: { fps, kbps, route } satisfies ScreenStats, sample: { bytes, at } };
}

/**
 * One screen session with a phone: asks the controller to start it, answers the phone's WebRTC offer and keeps
 * the media stream and the {@code control} data channel. Leaving the page stops the session.
 */
export function useScreenSession(deviceId: string) {
  const { client } = useRealtime();
  const [state, dispatch] = useReducer(screenReducer, initialScreenState);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [stats, setStats] = useState<ScreenStats>({ fps: null, kbps: null, route: null });
  const session = useRef<{ id: string; iceServers: RTCIceServer[]; control: boolean } | null>(null);
  const requesting = useRef(false);
  const early = useRef<Envelope[]>([]);
  const pc = useRef<RTCPeerConnection | null>(null);
  const channel = useRef<RTCDataChannel | null>(null);

  const teardown = useCallback(() => {
    channel.current?.close();
    channel.current = null;
    pc.current?.close();
    pc.current = null;
    session.current = null;
    setStream(null);
    setStats({ fps: null, kbps: null, route: null });
  }, []);

  const answerOffer = useCallback(
    async (sdp: string) => {
      const current = session.current;
      if (!current || pc.current) return;
      const connection = new RTCPeerConnection({ iceServers: current.iceServers });
      pc.current = connection;
      if (current.control) {
        const control = connection.createDataChannel("control", { negotiated: true, id: 0, ordered: true });
        control.onmessage = (event: MessageEvent) => {
          if (typeof event.data !== "string" || event.data.length > 1024) return;
          let parsed;
          try {
            parsed = phoneMessageSchema.safeParse(JSON.parse(event.data));
          } catch {
            return;
          }
          if (parsed.success && parsed.data.t === "control") {
            dispatch({ type: "controlAvailable", available: parsed.data.available });
          }
        };
        channel.current = control;
      }
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
        client.send("rtc.signal", { sessionId: current.id, kind: "ANSWER", sdp: connection.localDescription.sdp });
      } catch {
        if (pc.current !== connection) return;
        client.send("screen.stop", { sessionId: current.id });
        teardown();
        dispatch({ type: "connection", state: "failed" });
      }
    },
    [client, teardown],
  );

  const handle = useCallback(
    (message: Envelope) => {
      if (message.type === "screen.state") {
        const parsed = screenStateSchema.safeParse(message.payload);
        if (!parsed.success || parsed.data.sessionId !== session.current?.id) return;
        dispatch({ type: "phoneState", event: parsed.data });
        if (parsed.data.state === "ENDED") teardown();
      } else if (message.type === "rtc.signal") {
        const parsed = offerSchema.safeParse(message.payload);
        if (parsed.success && parsed.data.sessionId === session.current?.id) void answerOffer(parsed.data.sdp);
      }
    },
    [answerOffer, teardown],
  );

  useEffect(
    () =>
      client.subscribe((message) => {
        if (message.type !== "screen.state" && message.type !== "rtc.signal") return;
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
    (options: StartOptions) => {
      if (session.current) client.send("screen.stop", { sessionId: session.current.id });
      teardown();
      early.current = [];
      requesting.current = true;
      dispatch({ type: "request" });
      client
        .request("screen.start", { deviceId, ...options })
        .then((envelope) => {
          if (!requesting.current) return;
          const result = startResultSchema.parse(envelope.payload);
          requesting.current = false;
          session.current = {
            id: result.sessionId,
            control: result.control && options.control,
            iceServers: result.iceServers.map((server) => ({
              urls: server.urls,
              ...(server.username ? { username: server.username } : {}),
              ...(server.credential ? { credential: server.credential } : {}),
            })),
          };
          dispatch({ type: "started", result: { ...result, control: session.current.control } });
          const queued = early.current;
          early.current = [];
          queued.forEach(handle);
        })
        .catch((error: unknown) => {
          if (!requesting.current) return;
          requesting.current = false;
          dispatch({ type: "failed", message: relayErrorMessage(error) });
        });
    },
    [client, deviceId, handle, teardown],
  );

  const stop = useCallback(() => {
    requesting.current = false;
    if (session.current) client.send("screen.stop", { sessionId: session.current.id });
    teardown();
    dispatch({ type: "stop" });
  }, [client, teardown]);

  const sendControl = useCallback((message: ControlMessage) => {
    const control = channel.current;
    if (control?.readyState === "open") control.send(JSON.stringify(message));
  }, []);

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
      if (session.current) client.send("screen.stop", { sessionId: session.current.id });
      teardown();
    },
    [client, teardown],
  );

  return { state, stream, stats, start, stop, sendControl };
}
