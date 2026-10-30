import type { RtcStats } from "./peer";

/** Frame rate, bitrate and route of a live WebRTC stream, once known; audio-only streams show kbps. */
export function StatsLine({ stats, video = true }: { stats: RtcStats; video?: boolean }) {
  const bitrate = stats.kbps === null ? null : video ? `${(stats.kbps / 1000).toFixed(1)} Mbps` : `${stats.kbps} kbps`;
  const parts = [
    stats.fps !== null ? `${Math.round(stats.fps)} fps` : null,
    bitrate,
    stats.route === "relay" ? "relayed through TURN" : stats.route === "direct" ? "direct" : null,
  ].filter(Boolean);
  return parts.length > 0 ? <span className="text-xs text-neutral-500">{parts.join(" · ")}</span> : null;
}
