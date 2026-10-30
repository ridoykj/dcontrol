import { api } from "@/shared/api/client";
import type { Requester } from "@/shared/ws/client";
import { captureSchema } from "../lib/micSession";

/**
 * A clip can record for up to 5 min and then upload; the controller gives up after 6 min. Waiting a little longer
 * lets its TIMEOUT arrive instead of a local one.
 */
const CAPTURE_TIMEOUT_MS = 370_000;

export const micKeys = {
  media: (deviceId: string, mediaId: string) => ["phone", deviceId, "mic-media", mediaId] as const,
};

export function micMediaPath(deviceId: string, mediaId: string) {
  return `/api/devices/${encodeURIComponent(deviceId)}/mic/media/${encodeURIComponent(mediaId)}`;
}

/** The clip's bytes; kept 30 min on the controller, then 404. */
export function getMicMedia(deviceId: string, mediaId: string): Promise<Blob> {
  return api.requestBlob(micMediaPath(deviceId, mediaId));
}

/** Fails with CONFLICT while the session isn't live or another clip is recording. */
export async function captureClip(client: Requester, sessionId: string, durationMs: number) {
  return captureSchema.parse((await client.request("mic.capture", { sessionId, durationMs }, CAPTURE_TIMEOUT_MS)).payload);
}
