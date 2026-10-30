import type { MirroredNotification } from "../schemas/notificationSchemas";

/** Active notifications of one phone, newest first, keyed by StatusBarNotification key. */
export type NotificationsState = {
  status: "loading" | "ready" | "accessMissing" | "error";
  byKey: Record<string, MirroredNotification>;
};

export type NotificationsAction =
  | { type: "loading" }
  | { type: "loaded"; access: "GRANTED" | "MISSING"; items: MirroredNotification[]; append: boolean }
  | { type: "failed" }
  | { type: "posted"; notification: MirroredNotification }
  | { type: "removed"; key: string };

export const initialNotifications: NotificationsState = { status: "loading", byKey: {} };

export function notificationsReducer(state: NotificationsState, action: NotificationsAction): NotificationsState {
  switch (action.type) {
    case "loading":
      return { ...state, status: "loading" };
    case "loaded": {
      const byKey = action.append ? { ...state.byKey } : {};
      action.items.forEach((item) => {
        byKey[item.key] = item;
      });
      return { status: action.access === "MISSING" ? "accessMissing" : "ready", byKey };
    }
    case "failed":
      return { ...state, status: "error" };
    case "posted":
      // An update replaces the earlier version of the same notification.
      return {
        status: state.status === "accessMissing" ? "ready" : state.status,
        byKey: { ...state.byKey, [action.notification.key]: action.notification },
      };
    case "removed": {
      if (!state.byKey[action.key]) return state;
      const { [action.key]: _removed, ...byKey } = state.byKey;
      return { ...state, byKey };
    }
  }
}

export function sortedNotifications(state: NotificationsState): MirroredNotification[] {
  return Object.values(state.byKey).sort((a, b) => b.postedAt.localeCompare(a.postedAt));
}
