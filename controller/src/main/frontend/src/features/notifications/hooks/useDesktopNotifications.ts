import { useCallback, useState } from "react";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";
import { notificationPostedSchema } from "../schemas/notificationSchemas";

const STORAGE_KEY = "dcontrol.desktopNotifications";

function readPreference(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "on";
  } catch {
    return false;
  }
}

function writePreference(on: boolean) {
  try {
    localStorage.setItem(STORAGE_KEY, on ? "on" : "off");
  } catch {
    // storage unavailable (private mode); the switch still works for this page view
  }
}

export function desktopNotificationsSupported(): boolean {
  return typeof window !== "undefined" && "Notification" in window;
}

/**
 * Opt-in browser notifications for mirrored phone notifications, shown only while this tab is hidden.
 * The browser's permission is requested when the user switches it on.
 */
export function useDesktopNotifications(deviceId: string, deviceName: string) {
  const [enabled, setEnabled] = useState(
    () => desktopNotificationsSupported() && readPreference() && Notification.permission === "granted",
  );

  const toggle = useCallback(async (on: boolean) => {
    if (!desktopNotificationsSupported()) return;
    if (on && Notification.permission !== "granted") {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        setEnabled(false);
        writePreference(false);
        return;
      }
    }
    setEnabled(on);
    writePreference(on);
  }, []);

  useRealtimeEvent("notification.posted", notificationPostedSchema, ({ deviceId: from, notification }) => {
    if (!enabled || from !== deviceId || notification.ongoing || document.visibilityState === "visible") return;
    new Notification(notification.title || notification.appLabel, {
      body: notification.text ?? undefined,
      tag: `${deviceId}:${notification.key}`,
      silent: false,
      data: { deviceName },
    });
  });

  return { enabled, toggle, supported: desktopNotificationsSupported() };
}
