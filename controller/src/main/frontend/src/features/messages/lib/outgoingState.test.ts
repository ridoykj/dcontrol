import { describe, expect, it } from "vitest";
import type { SmsMessage } from "../schemas/smsSchemas";
import { outgoingReducer, unlistedOutgoing, type Outgoing } from "./outgoingState";

const SENT_AT = Date.parse("2026-10-29T10:00:00Z");

function sending(clientRef: string, text = "Hello"): Outgoing[] {
  return outgoingReducer([], { type: "sending", clientRef, to: "+49 151 2345678", text, at: SENT_AT });
}

function listed(overrides: Partial<SmsMessage> = {}): SmsMessage {
  return {
    id: 1,
    kind: "SMS",
    box: "SENT",
    address: "+491512345678",
    body: "Hello",
    date: "2026-10-29T10:00:02Z",
    read: true,
    status: "NONE",
    simLabel: null,
    mmsSummary: null,
    ...overrides,
  };
}

describe("outgoingReducer", () => {
  it("moves a message forward through queued, sent and delivered", () => {
    let state = sending("a");
    expect(state[0]?.status).toBe("sending");
    state = outgoingReducer(state, { type: "queued", clientRef: "a" });
    expect(state[0]?.status).toBe("queued");
    state = outgoingReducer(state, { type: "status", clientRef: "a", status: "DELIVERED" });
    state = outgoingReducer(state, { type: "status", clientRef: "a", status: "SENT" });
    expect(state[0]?.status).toBe("delivered");
  });

  it("keeps the failure reason and ignores unknown clientRefs", () => {
    let state = sending("a");
    state = outgoingReducer(state, { type: "status", clientRef: "b", status: "SENT" });
    expect(state[0]?.status).toBe("sending");
    state = outgoingReducer(state, { type: "status", clientRef: "a", status: "FAILED", errorCode: "RESULT_ERROR_NO_SERVICE" });
    expect(state[0]).toMatchObject({ status: "failed", error: "RESULT_ERROR_NO_SERVICE" });

    const rejected = outgoingReducer(sending("c"), { type: "rejected", clientRef: "c", error: "Too many requests" });
    expect(rejected[0]).toMatchObject({ status: "failed", error: "Too many requests" });
  });
});

describe("unlistedOutgoing", () => {
  it("hides a sent message once the phone lists it with the same body and number", () => {
    const state = outgoingReducer(sending("a"), { type: "status", clientRef: "a", status: "SENT" });
    expect(unlistedOutgoing(state, [listed()])).toEqual([]);
    expect(unlistedOutgoing(state, [listed({ body: "Other" })])).toHaveLength(1);
    expect(unlistedOutgoing(state, [listed({ box: "INBOX" })])).toHaveLength(1);
    expect(unlistedOutgoing(state, [listed({ date: "2026-10-29T11:00:00Z" })])).toHaveLength(1);
  });

  it("keeps failed messages visible", () => {
    const state = outgoingReducer(sending("a"), { type: "status", clientRef: "a", status: "FAILED" });
    expect(unlistedOutgoing(state, [listed({ box: "FAILED" })])).toHaveLength(1);
  });
});
