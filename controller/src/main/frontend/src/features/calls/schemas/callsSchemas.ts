import { z } from "zod";

export const CALL_TYPES = [
  "INCOMING",
  "OUTGOING",
  "MISSED",
  "VOICEMAIL",
  "REJECTED",
  "BLOCKED",
  "ANSWERED_EXTERNALLY",
  "OTHER",
] as const;

export type CallType = (typeof CALL_TYPES)[number];
/** Filters the phone accepts; OTHER is only a value the phone reports. */
export type CallFilter = Exclude<CallType, "OTHER">;

export const callTypeLabels: Record<CallType, string> = {
  INCOMING: "Incoming",
  OUTGOING: "Outgoing",
  MISSED: "Missed",
  VOICEMAIL: "Voicemail",
  REJECTED: "Rejected",
  BLOCKED: "Blocked",
  ANSWERED_EXTERNALLY: "Answered elsewhere",
  OTHER: "Other",
};

export const callEntrySchema = z.object({
  id: z.number(),
  number: z.string().nullish(),
  presentation: z.enum(["ALLOWED", "RESTRICTED", "UNKNOWN", "PAYPHONE"]),
  name: z.string().nullish(),
  type: z.enum(CALL_TYPES),
  date: z.string(),
  durationSec: z.number(),
  simLabel: z.string().nullish(),
  isNew: z.boolean(),
});

export const callsPageSchema = z.object({ items: z.array(callEntrySchema), nextCursor: z.string().nullish() });
export const callsDeletedSchema = z.object({ deleted: z.number() });

export type CallEntry = z.infer<typeof callEntrySchema>;

const presentationLabels = { RESTRICTED: "Private number", UNKNOWN: "Unknown", PAYPHONE: "Payphone" } as const;

/** Who the call was with, as the phone's dialer would show it. */
export function callParty(call: CallEntry): string {
  if (call.presentation !== "ALLOWED" || !call.number) {
    return call.presentation === "ALLOWED" ? "Unknown" : presentationLabels[call.presentation];
  }
  return call.name || call.number;
}

export function formatDuration(seconds: number): string {
  if (seconds <= 0) return "";
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  const mmss = `${String(minutes).padStart(hours > 0 ? 2 : 1, "0")}:${String(rest).padStart(2, "0")}`;
  return hours > 0 ? `${hours}:${mmss}` : mmss;
}
