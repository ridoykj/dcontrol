import { describe, expect, it } from "vitest";
import type { MirroredNotification } from "../schemas/notificationSchemas";
import { initialNotifications, notificationsReducer, sortedNotifications } from "./notificationsReducer";

function notification(key: string, postedAt: string, title = key): MirroredNotification {
  return {
    key,
    packageName: "com.example",
    appLabel: "Example",
    iconHash: null,
    title,
    text: "body",
    subText: null,
    postedAt,
    ongoing: false,
    clearable: true,
    canReply: false,
  };
}

describe("notificationsReducer", () => {
  it("loads pages, then applies posted and removed events", () => {
    let state = notificationsReducer(initialNotifications, {
      type: "loaded",
      access: "GRANTED",
      items: [notification("a", "2026-10-29T10:00:00Z")],
      append: false,
    });
    state = notificationsReducer(state, {
      type: "loaded",
      access: "GRANTED",
      items: [notification("b", "2026-10-29T10:05:00Z")],
      append: true,
    });
    expect(state.status).toBe("ready");
    expect(sortedNotifications(state).map((n) => n.key)).toEqual(["b", "a"]);

    state = notificationsReducer(state, { type: "posted", notification: notification("a", "2026-10-29T10:10:00Z", "updated") });
    expect(sortedNotifications(state).map((n) => n.title)).toEqual(["updated", "b"]);

    state = notificationsReducer(state, { type: "removed", key: "b" });
    expect(sortedNotifications(state).map((n) => n.key)).toEqual(["a"]);
    expect(notificationsReducer(state, { type: "removed", key: "missing" })).toBe(state);
  });

  it("a fresh load replaces what was there", () => {
    let state = notificationsReducer(initialNotifications, {
      type: "loaded",
      access: "GRANTED",
      items: [notification("old", "2026-10-29T09:00:00Z")],
      append: false,
    });
    state = notificationsReducer(state, { type: "loaded", access: "GRANTED", items: [], append: false });
    expect(sortedNotifications(state)).toEqual([]);
  });

  it("reports missing access until the phone starts posting", () => {
    let state = notificationsReducer(initialNotifications, { type: "loaded", access: "MISSING", items: [], append: false });
    expect(state.status).toBe("accessMissing");
    state = notificationsReducer(state, { type: "posted", notification: notification("x", "2026-10-29T10:00:00Z") });
    expect(state.status).toBe("ready");
  });
});
