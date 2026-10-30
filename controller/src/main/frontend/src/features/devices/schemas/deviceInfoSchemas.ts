import { z } from "zod";

/** Storage volume, shared by device.info and files.roots (ws-v1.md, Phase 2). */
export const volumeSchema = z.object({
  id: z.string(),
  label: z.string(),
  path: z.string(),
  totalBytes: z.number(),
  freeBytes: z.number(),
  removable: z.boolean(),
  primary: z.boolean(),
});

export const deviceInfoSchema = z.object({
  os: z.object({
    androidVersion: z.string(),
    sdkInt: z.number(),
    securityPatch: z.string().nullish(),
    buildId: z.string(),
    manufacturer: z.string(),
    model: z.string(),
    abis: z.array(z.string()),
  }),
  uptimeMs: z.number(),
  cpuCores: z.number(),
  memory: z.object({ totalBytes: z.number(), availableBytes: z.number(), lowMemory: z.boolean() }),
  storage: z.array(volumeSchema),
  battery: z.object({
    level: z.number(),
    charging: z.boolean(),
    health: z.enum(["GOOD", "OVERHEAT", "DEAD", "OVER_VOLTAGE", "COLD", "FAILURE", "UNKNOWN"]),
    plugged: z.enum(["AC", "USB", "WIRELESS", "DOCK", "NONE"]),
    temperatureC: z.number(),
    voltageMv: z.number(),
  }),
  display: z.object({ widthPx: z.number(), heightPx: z.number(), densityDpi: z.number(), refreshRateHz: z.number() }),
  network: z.object({
    type: z.enum(["WIFI", "CELLULAR", "ETHERNET", "VPN", "OTHER", "NONE"]),
    metered: z.boolean(),
    ipAddresses: z.array(z.string()),
  }),
  locale: z.string(),
  timeZone: z.string(),
});

export type Volume = z.infer<typeof volumeSchema>;
export type DeviceInfo = z.infer<typeof deviceInfoSchema>;
