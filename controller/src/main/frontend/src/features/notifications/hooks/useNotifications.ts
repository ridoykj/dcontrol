import { useCallback, useEffect, useReducer } from "react";
import type { Requester } from "@/shared/ws/client";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";
import { initialNotifications, notificationsReducer, sortedNotifications } from "../lib/notificationsReducer";
import {
  notificationPostedSchema,
  notificationRemovedSchema,
  notificationsListSchema,
  type MirroredNotification,
} from "../schemas/notificationSchemas";

const MAX_PAGES = 10;

/**
 * Live notifications of one phone: loads the active ones once (all pages), then applies pushed
 * posted/removed events. Nothing is stored on the controller, so a reload asks the phone again.
 */
export function useNotifications(client: Requester, deviceId: string, online: boolean) {
  const [state, dispatch] = useReducer(notificationsReducer, initialNotifications);

  const load = useCallback(async () => {
    dispatch({ type: "loading" });
    try {
      let offset: number | null | undefined = 0;
      for (let page = 0; page < MAX_PAGES && offset != null; page++) {
        const result = notificationsListSchema.parse(
          (await client.request("notifications.list", { deviceId, offset })).payload,
        );
        dispatch({ type: "loaded", access: result.access, items: result.items, append: page > 0 });
        offset = result.nextOffset;
      }
    } catch {
      dispatch({ type: "failed" });
    }
  }, [client, deviceId]);

  useEffect(() => {
    if (online) void load();
  }, [online, load]);

  useRealtimeEvent("notification.posted", notificationPostedSchema, (event) => {
    if (event.deviceId === deviceId) dispatch({ type: "posted", notification: event.notification });
  });
  useRealtimeEvent("notification.removed", notificationRemovedSchema, (event) => {
    if (event.deviceId === deviceId) dispatch({ type: "removed", key: event.key });
  });

  const dismiss = useCallback(
    async (notification: MirroredNotification) => {
      await client.request("notification.dismiss", { deviceId, key: notification.key });
      dispatch({ type: "removed", key: notification.key });
    },
    [client, deviceId],
  );

  const reply = useCallback(
    async (notification: MirroredNotification, text: string) => {
      await client.request("notification.reply", { deviceId, key: notification.key, text });
    },
    [client, deviceId],
  );

  return { status: state.status, notifications: sortedNotifications(state), reload: load, dismiss, reply };
}
