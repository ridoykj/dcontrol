import { z } from "zod";

export const batterySchema = z.object({
  level: z.number().int().min(0).max(100),
  charging: z.boolean(),
});

export const deviceStatusSchema = z.object({
  deviceId: z.string(),
  name: z.string(),
  online: z.boolean(),
  battery: batterySchema.nullable(),
  lastSeen: z.string().nullable(),
});

export const deviceSchema = z.object({
  id: z.string(),
  name: z.string(),
  manufacturer: z.string(),
  model: z.string(),
  androidVersion: z.string(),
  sdkInt: z.number(),
  appVersion: z.string(),
  state: z.enum(["ACTIVE", "REVOKED"]),
  active: z.boolean(),
  pairedAt: z.string(),
  modifiedAt: z.string(),
  status: deviceStatusSchema,
});

export const pairingSchema = z.object({
  id: z.string(),
  state: z.enum(["ISSUED", "CLAIMED", "CANCELLED"]),
  expiresAt: z.string(),
  deviceId: z.string().nullable(),
  qrPayload: z.string().nullable(),
});

export const renameSchema = z.object({
  name: z.string().trim().min(1, "Enter a name").max(64, "Use at most 64 characters"),
});

export const pingResultSchema = z.object({ rttMs: z.number(), deviceTime: z.string() });
export const devicesSnapshotSchema = z.object({ devices: z.array(deviceStatusSchema) });
export const pairingCompletedSchema = z.object({ pairingId: z.string(), deviceId: z.string() });

export type Battery = z.infer<typeof batterySchema>;
export type DeviceStatus = z.infer<typeof deviceStatusSchema>;
export type Device = z.infer<typeof deviceSchema>;
export type Pairing = z.infer<typeof pairingSchema>;
export type RenameValues = z.infer<typeof renameSchema>;
