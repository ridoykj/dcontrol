import type { RtcStats } from "./peer";

/** Frame rate, bitrate and route of a live WebRTC stream, once known. */
export function StatsLine({ stats }: { stats: RtcStats }) {
  const parts = [
    stats.fps !== null ? `${Math.round(stats.fps)} fps` : null,
    stats.kbps !== null ? `${(stats.kbps / 1000).toFixed(1)} Mbps` : null,
    stats.route === "relay" ? "relayed through TURN" : stats.route === "direct" ? "direct" : null,
  ].filter(Boolean);
  return parts.length > 0 ? <span className="text-xs text-neutral-500">{parts.join(" · ")}</span> : null;
}
