import type { SmsMessage } from "../schemas/smsSchemas";

export type OutgoingStatus = "sending" | "queued" | "sent" | "delivered" | "failed";

/** A message sent from this tab, shown until the phone's provider lists it. */
export type Outgoing = {
  clientRef: string;
  to: string;
  text: string;
  status: OutgoingStatus;
  createdAt: number;
  error?: string;
};

export type OutgoingAction =
  | { type: "sending"; clientRef: string; to: string; text: string; at: number }
  | { type: "queued"; clientRef: string }
  | { type: "status"; clientRef: string; status: "SENT" | "DELIVERED" | "FAILED"; errorCode?: string | null }
  | { type: "rejected"; clientRef: string; error: string };

const rank: Record<OutgoingStatus, number> = { sending: 0, queued: 1, sent: 2, delivered: 3, failed: 4 };

/** Keyed by clientRef; statuses only move forward, so a late SENT never hides a DELIVERED. */
export function outgoingReducer(state: Outgoing[], action: OutgoingAction): Outgoing[] {
  const update = (clientRef: string, change: (item: Outgoing) => Outgoing) =>
    state.map((item) => (item.clientRef === clientRef ? change(item) : item));
  const advance = (item: Outgoing, status: OutgoingStatus, error?: string): Outgoing =>
    rank[status] > rank[item.status] ? { ...item, status, error } : item;

  switch (action.type) {
    case "sending":
      return [
        ...state,
        { clientRef: action.clientRef, to: action.to, text: action.text, status: "sending", createdAt: action.at },
      ];
    case "queued":
      return update(action.clientRef, (item) => advance(item, "queued"));
    case "status": {
      const status = action.status === "SENT" ? "sent" : action.status === "DELIVERED" ? "delivered" : "failed";
      return update(action.clientRef, (item) => advance(item, status, action.errorCode ?? undefined));
    }
    case "rejected":
      return update(action.clientRef, (item) => ({ ...item, status: "failed", error: action.error }));
  }
}

const digits = (address: string | null | undefined) => (address ?? "").replace(/\D/g, "").slice(-9);
const MATCH_WINDOW_MS = 10 * 60_000;

/**
 * Outgoing messages the provider doesn't list yet. A sent message is matched by body, the last nine
 * digits of the address and a date close to when it was sent; failed ones stay visible until dismissed.
 */
export function unlistedOutgoing(outgoing: Outgoing[], listed: SmsMessage[]): Outgoing[] {
  return outgoing.filter((item) => {
    if (item.status === "failed") return true;
    return !listed.some(
      (message) =>
        message.box !== "INBOX" &&
        message.body === item.text &&
        digits(message.address) === digits(item.to) &&
        Math.abs(Date.parse(message.date) - item.createdAt) < MATCH_WINDOW_MS,
    );
  });
}
