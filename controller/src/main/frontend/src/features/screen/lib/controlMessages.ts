import { z } from "zod";
import type { Point } from "./pointer";

/** Messages on the {@code control} data channel (ws-v1.md, Phase 4). */
export const NAV_KEYS = ["back", "home", "recents", "notifications", "power", "lock"] as const;
export type NavKey = (typeof NAV_KEYS)[number];

export const MAX_TEXT = 256;
export const MAX_WHEEL = 2000;

export type ControlMessage =
  | { t: "down" | "move" | "up"; x: number; y: number }
  | { t: "wheel"; x: number; y: number; dy: number }
  | { t: "key"; k: NavKey }
  | { t: "text"; s: string }
  | { t: "edit"; k: "backspace" | "enter" };

export const phoneMessageSchema = z.discriminatedUnion("t", [
  z.object({ t: z.literal("size"), w: z.number().int().positive(), h: z.number().int().positive(), rotation: z.number().int() }),
  z.object({ t: z.literal("control"), available: z.boolean() }),
]);

export type PhoneMessage = z.infer<typeof phoneMessageSchema>;

/** Splits typed or pasted text into {@code text} messages of at most 256 characters, never splitting a code point. */
export function textMessages(text: string): ControlMessage[] {
  const messages: ControlMessage[] = [];
  let chunk = "";
  for (const codePoint of text) {
    if (chunk.length + codePoint.length > MAX_TEXT) {
      messages.push({ t: "text", s: chunk });
      chunk = "";
    }
    chunk += codePoint;
  }
  if (chunk) messages.push({ t: "text", s: chunk });
  return messages;
}

/**
 * One pointer's down/move/up stream. Moves are throttled to one per {@code intervalMs} (60 Hz); the last
 * skipped move is sent before {@code up}, so the phone's stroke ends where the pointer did.
 */
export function createPointerEncoder(send: (message: ControlMessage) => void, now: () => number, intervalMs = 16) {
  let down = false;
  let lastMoveAt = Number.NEGATIVE_INFINITY;
  let pendingMove: Point | null = null;
  return {
    get isDown() {
      return down;
    },
    down(point: Point) {
      down = true;
      pendingMove = null;
      lastMoveAt = now();
      send({ t: "down", ...point });
    },
    move(point: Point) {
      if (!down) return;
      if (now() - lastMoveAt >= intervalMs) {
        lastMoveAt = now();
        pendingMove = null;
        send({ t: "move", ...point });
      } else {
        pendingMove = point;
      }
    },
    up(point: Point) {
      if (!down) return;
      if (pendingMove) send({ t: "move", ...pendingMove });
      pendingMove = null;
      down = false;
      send({ t: "up", ...point });
    },
  };
}

/** Wheel deltas, summed and sent at most every {@code intervalMs}; each message becomes one swipe on the phone. */
export function createWheelEncoder(
  send: (message: ControlMessage) => void,
  schedule: (flush: () => void, delayMs: number) => void,
  intervalMs = 100,
) {
  let total = 0;
  let at: Point | null = null;
  let scheduled = false;
  const flush = () => {
    scheduled = false;
    if (!at || total === 0) return;
    const dy = Math.max(-MAX_WHEEL, Math.min(MAX_WHEEL, Math.round(total)));
    send({ t: "wheel", x: at.x, y: at.y, dy });
    total = 0;
  };
  return (point: Point, deltaY: number) => {
    at = point;
    total += deltaY;
    if (!scheduled) {
      scheduled = true;
      schedule(flush, intervalMs);
    }
  };
}
