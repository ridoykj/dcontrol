import type { Transfer, TransferProgress } from "../schemas/filesSchemas";

export type TransferItem = {
  transfer: Transfer;
  /** Bytes moved in the current leg: browser → controller while RECEIVING, controller → phone while READY. */
  progressBytes: number;
  /** Local failure (the upload from this browser gave up); the server state may still be open. */
  error?: string;
};

export type TransfersState = { items: Record<string, TransferItem>; order: string[] };

export type TransfersAction =
  | { type: "loaded"; transfers: Transfer[] }
  | { type: "status"; transfer: Transfer }
  | { type: "progress"; progress: TransferProgress }
  | { type: "localProgress"; id: string; bytes: number }
  | { type: "localError"; id: string; message: string }
  | { type: "dismiss"; id: string };

export const emptyTransfers: TransfersState = { items: {}, order: [] };

function upsert(state: TransfersState, transfer: Transfer, progressBytes?: number): TransfersState {
  const previous = state.items[transfer.id];
  const legChanged = previous && previous.transfer.state !== transfer.state;
  const bytes =
    progressBytes ??
    (transfer.state === "RECEIVING" ? transfer.receivedBytes : legChanged ? 0 : (previous?.progressBytes ?? 0));
  const items = { ...state.items, [transfer.id]: { ...previous, transfer, progressBytes: bytes } };
  const order = previous ? state.order : [transfer.id, ...state.order];
  return { items, order };
}

/** Transfers of one device: REST list, pushed transfer.status / transfer.progress, local upload progress. */
export function transfersReducer(state: TransfersState, action: TransfersAction): TransfersState {
  switch (action.type) {
    case "loaded": {
      // The list is newest first and older than anything pushed since the page opened: append what's new.
      const fresh = action.transfers.filter((transfer) => !state.items[transfer.id]);
      const items = { ...state.items };
      fresh.forEach((transfer) => {
        items[transfer.id] = {
          transfer,
          progressBytes: transfer.state === "RECEIVING" ? transfer.receivedBytes : 0,
        };
      });
      return { items, order: [...state.order, ...fresh.map((transfer) => transfer.id)] };
    }
    case "status":
      return upsert(state, action.transfer);
    case "progress": {
      const item = state.items[action.progress.transferId];
      if (!item) return state;
      return {
        ...state,
        items: { ...state.items, [item.transfer.id]: { ...item, progressBytes: action.progress.bytes } },
      };
    }
    case "localProgress": {
      const item = state.items[action.id];
      if (!item || item.transfer.state !== "RECEIVING") return state;
      return { ...state, items: { ...state.items, [action.id]: { ...item, progressBytes: action.bytes } } };
    }
    case "localError": {
      const item = state.items[action.id];
      if (!item) return state;
      return { ...state, items: { ...state.items, [action.id]: { ...item, error: action.message } } };
    }
    case "dismiss": {
      const { [action.id]: _removed, ...items } = state.items;
      return { items, order: state.order.filter((id) => id !== action.id) };
    }
  }
}

/** Starts a native browser download; the URL carries a single-use ticket instead of the bearer token. */
export function startBrowserDownload(url: string, doc: Document = document) {
  const anchor = doc.createElement("a");
  anchor.href = url;
  anchor.rel = "noopener";
  anchor.style.display = "none";
  doc.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}
