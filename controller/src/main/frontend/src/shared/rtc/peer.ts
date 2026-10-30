const GATHERING_TIMEOUT_MS = 4000;

export type RtcStats = { fps: number | null; kbps: number | null; route: "direct" | "relay" | null };

export const noStats: RtcStats = { fps: null, kbps: null, route: null };

/** The controller's ICE servers in the shape RTCPeerConnection takes, without empty credentials. */
export function toRtcIceServers(
  servers: { urls: string[]; username?: string | null; credential?: string | null }[],
): RTCIceServer[] {
  return servers.map((server) => ({
    urls: server.urls,
    ...(server.username ? { username: server.username } : {}),
    ...(server.credential ? { credential: server.credential } : {}),
  }));
}

/** Resolves once ICE gathering is complete (non-trickle signaling), or after 4 s with what was gathered. */
export function gatheringComplete(pc: RTCPeerConnection): Promise<void> {
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

/** Frame rate, received bitrate since {@code previous} and whether the media goes direct or through TURN. */
export async function readStats(pc: RTCPeerConnection, previous: { bytes: number; at: number } | null) {
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
  let route: RtcStats["route"] = null;
  if (pairId) {
    const pair = report.get(pairId) as { localCandidateId?: string } | undefined;
    const type = pair?.localCandidateId ? candidates.get(pair.localCandidateId) : undefined;
    if (type) route = type === "relay" ? "relay" : "direct";
  }
  const at = performance.now();
  const kbps = previous && at > previous.at ? Math.round(((bytes - previous.bytes) * 8) / (at - previous.at)) : null;
  return { stats: { fps, kbps, route } satisfies RtcStats, sample: { bytes, at } };
}
