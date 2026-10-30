import { z } from "zod";
import { api } from "@/shared/api/client";
import { deviceSchema, pairingSchema } from "../schemas/deviceSchemas";

export const deviceKeys = {
  all: ["devices"] as const,
  lists: () => [...deviceKeys.all, "list"] as const,
  list: (deleted: boolean) => [...deviceKeys.lists(), { deleted }] as const,
  detail: (id: string) => [...deviceKeys.all, "detail", id] as const,
};

export function listDevices(deleted: boolean) {
  return api.request(`/api/devices?deleted=${deleted}`, z.array(deviceSchema));
}

export function getDevice(id: string) {
  return api.request(`/api/devices/${encodeURIComponent(id)}`, deviceSchema);
}

export function renameDevice(id: string, name: string) {
  return api.request(`/api/devices/${encodeURIComponent(id)}`, deviceSchema, { method: "PATCH", json: { name } });
}

export function deleteDevice(id: string) {
  return api.request(`/api/devices/${encodeURIComponent(id)}`, null, { method: "DELETE" });
}

export function restoreDevice(id: string) {
  return api.request(`/api/devices/${encodeURIComponent(id)}/restore`, deviceSchema, { method: "POST" });
}

export function createPairing() {
  return api.request("/api/pairings", pairingSchema, { method: "POST" });
}

export function getPairing(id: string) {
  return api.request(`/api/pairings/${encodeURIComponent(id)}`, pairingSchema);
}

export function cancelPairing(id: string) {
  return api.request(`/api/pairings/${encodeURIComponent(id)}`, null, { method: "DELETE" });
}
