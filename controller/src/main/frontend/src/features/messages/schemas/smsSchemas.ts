import { z } from "zod";

export const smsThreadSchema = z.object({
  threadId: z.number(),
  participants: z.array(z.object({ address: z.string(), name: z.string().nullish() })),
  snippet: z.string().nullish(),
  date: z.string(),
  unreadCount: z.number(),
  messageCount: z.number(),
  hasMms: z.boolean(),
});

export const smsMessageSchema = z.object({
  id: z.number(),
  kind: z.enum(["SMS", "MMS"]),
  box: z.enum(["INBOX", "SENT", "OUTBOX", "FAILED", "QUEUED", "DRAFT"]),
  address: z.string().nullish(),
  body: z.string().nullish(),
  date: z.string(),
  read: z.boolean(),
  status: z.enum(["NONE", "PENDING", "COMPLETE", "FAILED"]),
  simLabel: z.string().nullish(),
  mmsSummary: z.string().nullish(),
});

export const threadsPageSchema = z.object({ items: z.array(smsThreadSchema), nextCursor: z.string().nullish() });
export const messagesPageSchema = z.object({
  threadId: z.number(),
  items: z.array(smsMessageSchema),
  nextCursor: z.string().nullish(),
});

export const smsReceivedSchema = z.object({ deviceId: z.string(), threadId: z.number(), message: smsMessageSchema });
export const smsStatusSchema = z.object({
  deviceId: z.string(),
  clientRef: z.string(),
  status: z.enum(["SENT", "DELIVERED", "FAILED"]),
  errorCode: z.string().nullish(),
});
export const deviceHintSchema = z.object({ deviceId: z.string() });
export const sendResultSchema = z.object({ parts: z.number() });

export const MAX_SMS_TEXT = 1600;
/** The phone answers PAYLOAD_TOO_LARGE above this; 1600 non-GSM characters would need about 24 parts. */
export const MAX_SMS_PARTS = 20;
export const composeSchema = z.object({
  text: z.string().trim().min(1, "Write a message").max(MAX_SMS_TEXT, `Use at most ${MAX_SMS_TEXT} characters`),
});

export type SmsThread = z.infer<typeof smsThreadSchema>;
export type SmsMessage = z.infer<typeof smsMessageSchema>;
