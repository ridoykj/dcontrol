import { z } from "zod";
import { api } from "@/shared/api/client";
import type { Requester } from "@/shared/ws/client";
import {
  deleteResultSchema,
  downloadTicketSchema,
  entryResultSchema,
  listResultSchema,
  rootsResultSchema,
  transferSchema,
  type Transfer,
} from "../schemas/filesSchemas";

export const filesKeys = {
  all: (deviceId: string) => ["files", deviceId] as const,
  roots: (deviceId: string) => [...filesKeys.all(deviceId), "roots"] as const,
  dir: (deviceId: string, path: string) => [...filesKeys.all(deviceId), "dir", path] as const,
  transfers: (deviceId: string) => ["transfers", deviceId] as const,
};

// ---- commands relayed to the phone over the WebSocket ----

export async function listRoots(client: Requester, deviceId: string) {
  return rootsResultSchema.parse((await client.request("files.roots", { deviceId })).payload).volumes;
}

export async function listDirectory(client: Requester, deviceId: string, path: string, offset: number) {
  return listResultSchema.parse((await client.request("files.list", { deviceId, path, offset })).payload);
}

export async function makeDirectory(client: Requester, deviceId: string, path: string, name: string) {
  return entryResultSchema.parse((await client.request("files.mkdir", { deviceId, path, name })).payload).entry;
}

export async function renameEntry(client: Requester, deviceId: string, path: string, newName: string) {
  return entryResultSchema.parse((await client.request("files.rename", { deviceId, path, newName })).payload).entry;
}

export async function deleteEntries(client: Requester, deviceId: string, paths: string[]) {
  return deleteResultSchema.parse((await client.request("files.delete", { deviceId, paths })).payload);
}

// ---- transfers over HTTP (docs/protocol/transfers-v1.md) ----

export function createUpload(deviceId: string, targetDir: string, file: { name: string; size: number }) {
  return api.request(`/api/devices/${encodeURIComponent(deviceId)}/transfers`, transferSchema, {
    json: { direction: "TO_DEVICE", targetDir, fileName: file.name, size: file.size },
  });
}

export function createDownload(deviceId: string, path: string) {
  return api.request(`/api/devices/${encodeURIComponent(deviceId)}/transfers`, transferSchema, {
    json: { direction: "FROM_DEVICE", path },
  });
}

export function listTransfers(deviceId: string) {
  return api.request(`/api/devices/${encodeURIComponent(deviceId)}/transfers`, z.array(transferSchema));
}

export function getTransfer(id: string): Promise<Transfer> {
  return api.request(`/api/transfers/${encodeURIComponent(id)}`, transferSchema);
}

export function cancelTransfer(id: string) {
  return api.request(`/api/transfers/${encodeURIComponent(id)}/cancel`, transferSchema, { method: "POST" });
}

export function downloadTicket(id: string) {
  return api.request(`/api/transfers/${encodeURIComponent(id)}/download-ticket`, downloadTicketSchema, {
    method: "POST",
  });
}

export function putChunk(id: string, chunk: Blob, start: number, total: number, signal?: AbortSignal) {
  return api.request(`/api/transfers/${encodeURIComponent(id)}/content`, transferSchema, {
    method: "PUT",
    body: chunk,
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Range": `bytes ${start}-${start + chunk.size - 1}/${total}`,
    },
    signal,
  });
}
