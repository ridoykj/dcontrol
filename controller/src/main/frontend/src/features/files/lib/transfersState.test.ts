import { describe, expect, it } from "vitest";
import type { Transfer } from "../schemas/filesSchemas";
import { emptyTransfers, startBrowserDownload, transfersReducer } from "./transfersState";

function transfer(id: string, patch: Partial<Transfer> = {}): Transfer {
  return {
    id,
    deviceId: "d1",
    direction: "FROM_DEVICE",
    state: "RECEIVING",
    fileName: `${id}.jpg`,
    devicePath: `/storage/emulated/0/DCIM/${id}.jpg`,
    size: 100,
    receivedBytes: 0,
    sha256: null,
    errorCode: null,
    createdAt: "2026-10-29T10:00:00Z",
    expiresAt: "2026-10-29T10:15:00Z",
    finishedAt: null,
    ...patch,
  };
}

describe("transfersReducer", () => {
  it("keeps newest first and does not let the REST list overwrite live state", () => {
    let state = transfersReducer(emptyTransfers, { type: "status", transfer: transfer("b", { receivedBytes: 40 }) });
    state = transfersReducer(state, { type: "loaded", transfers: [transfer("b"), transfer("a")] });
    expect(state.order).toEqual(["b", "a"]);
    expect(state.items.b?.progressBytes).toBe(40);
  });

  it("tracks progress per leg", () => {
    let state = transfersReducer(emptyTransfers, {
      type: "status",
      transfer: transfer("t", { direction: "TO_DEVICE" }),
    });
    state = transfersReducer(state, { type: "localProgress", id: "t", bytes: 60 });
    expect(state.items.t?.progressBytes).toBe(60);

    state = transfersReducer(state, {
      type: "status",
      transfer: transfer("t", { direction: "TO_DEVICE", state: "READY", receivedBytes: 100 }),
    });
    expect(state.items.t?.progressBytes).toBe(0);
    state = transfersReducer(state, {
      type: "progress",
      progress: { deviceId: "d1", transferId: "t", bytes: 30, size: 100 },
    });
    expect(state.items.t?.progressBytes).toBe(30);
    state = transfersReducer(state, { type: "localProgress", id: "t", bytes: 99 });
    expect(state.items.t?.progressBytes).toBe(30);
  });

  it("ignores progress for unknown transfers, records local errors and dismisses", () => {
    let state = transfersReducer(emptyTransfers, {
      type: "progress",
      progress: { deviceId: "d1", transferId: "x", bytes: 1, size: 2 },
    });
    expect(state).toBe(emptyTransfers);
    state = transfersReducer(state, { type: "status", transfer: transfer("t") });
    state = transfersReducer(state, { type: "localError", id: "t", message: "Network down" });
    expect(state.items.t?.error).toBe("Network down");
    state = transfersReducer(state, { type: "dismiss", id: "t" });
    expect(state.order).toEqual([]);
  });
});

describe("startBrowserDownload", () => {
  it("clicks a temporary link to the ticket URL", () => {
    const clicked: string[] = [];
    const original = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
      clicked.push(this.getAttribute("href") ?? "");
    };
    try {
      startBrowserDownload("/api/transfers/t/content?ticket=abc");
    } finally {
      HTMLAnchorElement.prototype.click = original;
    }
    expect(clicked).toEqual(["/api/transfers/t/content?ticket=abc"]);
    expect(document.querySelector("a")).toBeNull();
  });
});
