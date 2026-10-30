import { api } from "@/shared/api/client";
import { ApiError } from "@/shared/api/problem";
import { storedLocationSchema, type StoredLocation } from "../schemas/findSchemas";

export const locationKeys = {
  detail: (deviceId: string) => ["devices", deviceId, "location"] as const,
};

const path = (deviceId: string) => `/api/devices/${encodeURIComponent(deviceId)}/location`;

/** The stored last fix, or null when none is stored. */
export async function getLocation(deviceId: string): Promise<StoredLocation | null> {
  try {
    return await api.request(path(deviceId), storedLocationSchema);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/** Asks the phone where it is now; the controller stores the answer as the new last fix. */
export function refreshLocation(deviceId: string): Promise<StoredLocation> {
  return api.request(`${path(deviceId)}/refresh`, storedLocationSchema, { method: "POST" });
}

export function clearLocation(deviceId: string): Promise<void> {
  return api.request(path(deviceId), null, { method: "DELETE" });
}
