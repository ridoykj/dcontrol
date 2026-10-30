import { z } from "zod";
import { volumeSchema } from "@/features/devices/schemas/deviceInfoSchemas";

export const fileEntrySchema = z.object({
  name: z.string(),
  path: z.string(),
  dir: z.boolean(),
  size: z.number(),
  modified: z.string(),
  mime: z.string().nullish(),
  hidden: z.boolean(),
});

export const rootsResultSchema = z.object({ volumes: z.array(volumeSchema) });

export const listResultSchema = z.object({
  path: z.string(),
  entries: z.array(fileEntrySchema),
  total: z.number(),
  nextOffset: z.number().nullish(),
});

export const entryResultSchema = z.object({ entry: fileEntrySchema });

export const deleteResultSchema = z.object({
  deleted: z.number(),
  failures: z.array(z.object({ path: z.string(), code: z.string(), message: z.string().nullish() })),
});

export const transferStateSchema = z.enum(["RECEIVING", "READY", "DELIVERED", "FAILED", "CANCELLED", "EXPIRED"]);

/** Transfer resource (docs/protocol/transfers-v1.md); also the payload of transfer.status. */
export const transferSchema = z.object({
  id: z.string(),
  deviceId: z.string(),
  direction: z.enum(["TO_DEVICE", "FROM_DEVICE"]),
  state: transferStateSchema,
  fileName: z.string(),
  devicePath: z.string(),
  size: z.number().nullable(),
  receivedBytes: z.number(),
  sha256: z.string().nullable(),
  errorCode: z.string().nullable(),
  createdAt: z.string(),
  expiresAt: z.string(),
  finishedAt: z.string().nullable(),
});

export const transferProgressSchema = z.object({
  deviceId: z.string(),
  transferId: z.string(),
  bytes: z.number(),
  size: z.number(),
});

export const downloadTicketSchema = z.object({ ticket: z.string(), url: z.string(), expiresAt: z.string() });

/** A single path segment, as the phone accepts it. */
export const fileNameSchema = z
  .string()
  .trim()
  .min(1, "Enter a name")
  .max(255, "Use at most 255 characters")
  .refine((name) => !name.includes("/"), "A name can't contain '/'")
  .refine((name) => name !== "." && name !== "..", "Choose another name");

export type FileEntry = z.infer<typeof fileEntrySchema>;
export type ListResult = z.infer<typeof listResultSchema>;
export type DeleteResult = z.infer<typeof deleteResultSchema>;
export type Transfer = z.infer<typeof transferSchema>;
export type TransferState = z.infer<typeof transferStateSchema>;
export type TransferProgress = z.infer<typeof transferProgressSchema>;

export function isOpen(state: TransferState): boolean {
  return state === "RECEIVING" || state === "READY";
}
