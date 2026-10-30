import { api } from "@/shared/api/client";
import type { Requester } from "@/shared/ws/client";
import { type CaptureKind, captureSchema, type Facing, switchResultSchema } from "../lib/cameraSession";

/**
 * A clip can record for up to 60 s and then upload; the controller gives up after 2 min. Waiting a little longer
 * lets its TIMEOUT arrive instead of a local one.
 */
const CAPTURE_TIMEOUT_MS = 130_000;

export const cameraKeys = {
  media: (deviceId: string, mediaId: string) => ["phone", deviceId, "camera-media", mediaId] as const,
};

export function cameraMediaPath(deviceId: string, mediaId: string) {
  return `/api/devices/${encodeURIComponent(deviceId)}/camera/media/${encodeURIComponent(mediaId)}`;
}

/** The capture's bytes; kept 30 min on the controller, then 404. */
export function getCameraMedia(deviceId: string, mediaId: string): Promise<Blob> {
  return api.requestBlob(cameraMediaPath(deviceId, mediaId));
}

/** Fails with CONFLICT while the session isn't live or another capture is running. */
export async function captureMedia(client: Requester, sessionId: string, kind: CaptureKind, durationMs: number | null) {
  const payload = { sessionId, kind, durationMs: kind === "VIDEO" ? durationMs : null };
  return captureSchema.parse((await client.request("camera.capture", payload, CAPTURE_TIMEOUT_MS)).payload);
}

export async function switchCamera(client: Requester, sessionId: string, facing: Facing) {
  return switchResultSchema.parse((await client.request("camera.switch", { sessionId, facing })).payload).facing;
}

/** Saves an already-fetched capture; the bytes are in memory, so no download ticket is needed. */
export function saveBlob(blob: Blob, fileName: string, doc: Document = document) {
  const url = URL.createObjectURL(blob);
  const anchor = doc.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = "none";
  doc.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
