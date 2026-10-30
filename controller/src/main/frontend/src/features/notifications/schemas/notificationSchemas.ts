import { z } from "zod";

export const notificationSchema = z.object({
  key: z.string(),
  packageName: z.string(),
  appLabel: z.string(),
  iconHash: z.string().nullish(),
  title: z.string().nullish(),
  text: z.string().nullish(),
  subText: z.string().nullish(),
  postedAt: z.string(),
  ongoing: z.boolean(),
  clearable: z.boolean(),
  canReply: z.boolean(),
});

export const notificationsListSchema = z.object({
  access: z.enum(["GRANTED", "MISSING"]),
  items: z.array(notificationSchema),
  nextOffset: z.number().nullish(),
});

export const notificationPostedSchema = z.object({ deviceId: z.string(), notification: notificationSchema });
export const notificationRemovedSchema = z.object({ deviceId: z.string(), key: z.string() });

export const replySchema = z.object({
  text: z.string().trim().min(1, "Write a reply").max(1000, "Use at most 1000 characters"),
});

export type MirroredNotification = z.infer<typeof notificationSchema>;
