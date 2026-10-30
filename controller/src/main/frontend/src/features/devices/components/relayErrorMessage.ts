import { errorMessage } from "@/shared/api/problem";
import { RealtimeError } from "@/shared/ws/client";

const messages: Record<string, string> = {
  DEVICE_OFFLINE: "The phone is offline.",
  DEVICE_DISCONNECTED: "The phone disconnected before answering.",
  TIMEOUT: "The phone did not answer in time.",
  DISCONNECTED: "Not connected to the server. Reconnecting…",
  UNSUPPORTED: "The app on the phone doesn't support this yet. Update it.",
  RATE_LIMITED: "Too many requests for this phone. Wait a minute and try again.",
  PERMISSION_REQUIRED: "The phone hasn't granted the permission this needs. Grant it in dcontrol on the phone.",
};

/** User-facing text for a failed relayed command. */
export function relayErrorMessage(error: unknown): string {
  const known = error instanceof RealtimeError ? messages[error.code] : undefined;
  if (known) return known;
  return errorMessage(error);
}
