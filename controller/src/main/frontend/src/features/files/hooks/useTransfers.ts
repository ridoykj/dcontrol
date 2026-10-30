import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useReducer, useRef } from "react";
import { errorMessage } from "@/shared/api/problem";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";
import {
  cancelTransfer,
  createDownload,
  createUpload,
  downloadTicket,
  filesKeys,
  getTransfer,
  listTransfers,
  putChunk,
} from "../api/filesApi";
import { uploadInChunks } from "../lib/chunkedUpload";
import { emptyTransfers, startBrowserDownload, transfersReducer } from "../lib/transfersState";
import { transferProgressSchema, transferSchema, type Transfer } from "../schemas/filesSchemas";

/**
 * Transfers of one device, kept live from the socket. Uploads run in this tab; downloads open the
 * browser's own download once the phone has staged the file on the controller.
 */
export function useTransfers(deviceId: string, onDelivered?: (transfer: Transfer) => void) {
  const [state, dispatch] = useReducer(transfersReducer, emptyTransfers);
  const uploads = useRef(new Map<string, AbortController>());
  const wantDownload = useRef(new Set<string>());
  const delivered = useRef(onDelivered);
  delivered.current = onDelivered;

  const initial = useQuery({ queryKey: filesKeys.transfers(deviceId), queryFn: () => listTransfers(deviceId) });
  useEffect(() => {
    if (initial.data) dispatch({ type: "loaded", transfers: initial.data });
  }, [initial.data]);

  const fetchDownload = useCallback(async (transfer: Transfer) => {
    wantDownload.current.delete(transfer.id);
    try {
      const ticket = await downloadTicket(transfer.id);
      startBrowserDownload(ticket.url);
    } catch (error) {
      dispatch({ type: "localError", id: transfer.id, message: errorMessage(error) });
    }
  }, []);

  useRealtimeEvent("transfer.status", transferSchema, (transfer) => {
    if (transfer.deviceId !== deviceId) return;
    dispatch({ type: "status", transfer });
    if (transfer.state === "READY" && wantDownload.current.has(transfer.id)) void fetchDownload(transfer);
    if (transfer.state === "DELIVERED" && transfer.direction === "TO_DEVICE") delivered.current?.(transfer);
    if (transfer.state !== "RECEIVING" && transfer.state !== "READY") wantDownload.current.delete(transfer.id);
  });
  useRealtimeEvent("transfer.progress", transferProgressSchema, (progress) => {
    if (progress.deviceId === deviceId) dispatch({ type: "progress", progress });
  });

  useEffect(() => {
    const running = uploads.current;
    return () => running.forEach((controller) => controller.abort());
  }, []);

  const upload = useCallback(
    async (file: File, targetDir: string) => {
      const transfer = await createUpload(deviceId, targetDir, file);
      dispatch({ type: "status", transfer });
      if (transfer.state !== "RECEIVING") return;
      const controller = new AbortController();
      uploads.current.set(transfer.id, controller);
      try {
        const done = await uploadInChunks(file, transfer, { putChunk, getTransfer }, {
          signal: controller.signal,
          onProgress: (bytes) => dispatch({ type: "localProgress", id: transfer.id, bytes }),
        });
        dispatch({ type: "status", transfer: done });
      } catch (error) {
        if (controller.signal.aborted) return;
        dispatch({ type: "localError", id: transfer.id, message: errorMessage(error) });
        await cancelTransfer(transfer.id).catch(() => undefined);
      } finally {
        uploads.current.delete(transfer.id);
      }
    },
    [deviceId],
  );

  const download = useCallback(
    async (path: string) => {
      const transfer = await createDownload(deviceId, path);
      dispatch({ type: "status", transfer });
      if (transfer.state === "READY") {
        await fetchDownload(transfer);
        return;
      }
      wantDownload.current.add(transfer.id);
      // The READY push may have arrived before this response; check once so it isn't missed.
      const latest = await getTransfer(transfer.id);
      if (latest.state === "READY" && wantDownload.current.has(transfer.id)) await fetchDownload(latest);
    },
    [deviceId, fetchDownload],
  );

  const cancel = useCallback(async (id: string) => {
    uploads.current.get(id)?.abort();
    try {
      dispatch({ type: "status", transfer: await cancelTransfer(id) });
    } catch (error) {
      dispatch({ type: "localError", id, message: errorMessage(error) });
    }
  }, []);

  const saveAgain = useCallback((transfer: Transfer) => void fetchDownload(transfer), [fetchDownload]);
  const dismiss = useCallback((id: string) => dispatch({ type: "dismiss", id }), []);

  return {
    items: state.order.flatMap((id) => {
      const item = state.items[id];
      return item ? [item] : [];
    }),
    upload,
    download,
    cancel,
    saveAgain,
    dismiss,
  };
}
