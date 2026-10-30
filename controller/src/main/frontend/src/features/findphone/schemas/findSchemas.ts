import { z } from "zod";

export const PROVIDERS = ["GPS", "NETWORK", "FUSED", "PASSIVE", "OTHER"] as const;

export const storedLocationSchema = z.object({
  deviceId: z.string(),
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
  accuracyM: z.number().nonnegative(),
  altitudeM: z.number().nullable(),
  fixedAt: z.string(),
  provider: z.enum(PROVIDERS),
  stale: z.boolean(),
  updatedAt: z.string(),
});
export type StoredLocation = z.infer<typeof storedLocationSchema>;

export const providerLabels: Record<StoredLocation["provider"], string> = {
  GPS: "GPS",
  NETWORK: "Wi-Fi / mobile network",
  FUSED: "Combined",
  PASSIVE: "Another app's fix",
  OTHER: "Other",
};

/** Mirrors the controller's {@code find.lock} rules: 1–200 chars, line breaks are the only control characters. */
const LOCK_MESSAGE = /^(?:[^\u0000-\u001f\u007f]|\n){1,200}$/u;
const PHONE_NUMBER = /^[^\u0000-\u001f\u007f]{1,64}$/u;

export const lockFormSchema = z.object({
  message: z
    .string()
    .trim()
    .min(1, "Write a message for whoever finds the phone")
    .max(200, "At most 200 characters")
    .regex(LOCK_MESSAGE, "Only text and line breaks"),
  contact: z
    .string()
    .trim()
    .max(64, "At most 64 characters")
    .refine((value) => value === "" || PHONE_NUMBER.test(value), "Only text")
    .optional(),
});
export type LockForm = z.infer<typeof lockFormSchema>;

/** Six decimals is about 10 cm, as precise as the controller stores. */
export function formatCoordinates(latitude: number, longitude: number): string {
  const lat = `${Math.abs(latitude).toFixed(6)}° ${latitude < 0 ? "S" : "N"}`;
  const lon = `${Math.abs(longitude).toFixed(6)}° ${longitude < 0 ? "W" : "E"}`;
  return `${lat}, ${lon}`;
}

export function formatAccuracy(meters: number): string {
  return meters >= 1000 ? `±${(meters / 1000).toFixed(1)} km` : `±${Math.round(meters)} m`;
}

/** Links that open the position in a map site; the coordinates are the only thing they carry. */
export function mapLinks(latitude: number, longitude: number) {
  const lat = latitude.toFixed(6);
  const lon = longitude.toFixed(6);
  return {
    openStreetMap: `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=17/${lat}/${lon}`,
    googleMaps: `https://www.google.com/maps/search/?api=1&query=${lat}%2C${lon}`,
  };
}

/** "just now", "5 min ago", "3 h ago", or the date for anything older than a day. */
export function ago(iso: string, now: number = Date.now()): string {
  const millis = now - new Date(iso).getTime();
  const minutes = Math.floor(millis / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(iso).toLocaleString();
}
