import { ApiError } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { RealtimeError } from "@/shared/ws/client";

export type PhonePermission = "CONTACTS" | "CALL_LOG" | "SMS" | "PHONE" | "LOCATION" | "ACCESSIBILITY";

const cards: Record<PhonePermission, { card: string; what: string; action: string }> = {
  CONTACTS: { card: "Contacts", what: "read and edit contacts", action: "Grant" },
  CALL_LOG: { card: "Calls", what: "read and delete the call log", action: "Grant" },
  SMS: { card: "Messages", what: "read and send SMS", action: "Grant" },
  PHONE: { card: "Calls", what: "place calls", action: "Grant" },
  LOCATION: { card: "Find my phone", what: "share its location", action: "Grant" },
  ACCESSIBILITY: { card: "Remote control", what: "control or lock the screen", action: "Turn on" },
};

/** The phone access a failed relayed command or REST call is missing, or null for any other failure. */
export function missingPermission(error: unknown): PhonePermission | null {
  const permission =
    error instanceof RealtimeError || error instanceof ApiError
      ? error.code === "PERMISSION_REQUIRED"
        ? error.permission
        : undefined
      : undefined;
  return permission && permission in cards ? (permission as PhonePermission) : null;
}

/** Explains how to grant a phone permission; Android never lets the web or the app grant it silently. */
export function PermissionRequired({ permission }: { permission: PhonePermission }) {
  const { card, what, action } = cards[permission];
  return (
    <Alert>
      The phone hasn't allowed dcontrol to {what}. Open dcontrol on the phone and tap <strong>{action}</strong> on the{" "}
      <strong>{card}</strong> card.
      {permission === "LOCATION" && (
        <>
          {" "}
          Choose <strong>Allow all the time</strong>: the phone is located while dcontrol runs in the background.
        </>
      )}
      {permission === "SMS" && (
        <>
          {" "}
          On Android 15 and later, an app installed outside the Play Store needs <strong>App info → ⋮ → Allow
          restricted settings</strong> first.
        </>
      )}
      {permission === "ACCESSIBILITY" && (
        <>
          {" "}
          On Android 13 and later, an app installed outside the Play Store needs <strong>App info → ⋮ → Allow
          restricted settings</strong> before the service can be turned on.
        </>
      )}
    </Alert>
  );
}
