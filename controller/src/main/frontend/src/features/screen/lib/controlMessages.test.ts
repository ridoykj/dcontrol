import { describe, expect, it } from "vitest";
import {
  type ControlMessage,
  createPointerEncoder,
  createWheelEncoder,
  MAX_TEXT,
  phoneMessageSchema,
  textMessages,
} from "./controlMessages";

describe("createPointerEncoder", () => {
  it("throttles moves to one per interval and flushes the last one before up", () => {
    const sent: ControlMessage[] = [];
    let now = 0;
    const pointer = createPointerEncoder((message) => sent.push(message), () => now);

    pointer.move({ x: 0.1, y: 0.1 });
    expect(sent).toEqual([]);

    pointer.down({ x: 0.1, y: 0.1 });
    now = 5;
    pointer.move({ x: 0.2, y: 0.2 });
    now = 10;
    pointer.move({ x: 0.3, y: 0.3 });
    now = 20;
    pointer.move({ x: 0.4, y: 0.4 });
    now = 25;
    pointer.move({ x: 0.5, y: 0.5 });
    pointer.up({ x: 0.6, y: 0.6 });
    pointer.up({ x: 0.7, y: 0.7 });

    expect(sent).toEqual([
      { t: "down", x: 0.1, y: 0.1 },
      { t: "move", x: 0.4, y: 0.4 },
      { t: "move", x: 0.5, y: 0.5 },
      { t: "up", x: 0.6, y: 0.6 },
    ]);
    expect(pointer.isDown).toBe(false);
  });
});

describe("createWheelEncoder", () => {
  it("sums deltas per interval and caps them", () => {
    const sent: ControlMessage[] = [];
    const flushes: (() => void)[] = [];
    const wheel = createWheelEncoder((message) => sent.push(message), (flush) => flushes.push(flush));

    wheel({ x: 0.5, y: 0.5 }, 100);
    wheel({ x: 0.5, y: 0.6 }, 120);
    expect(flushes).toHaveLength(1);
    flushes[0]!();
    expect(sent).toEqual([{ t: "wheel", x: 0.5, y: 0.6, dy: 220 }]);

    wheel({ x: 0.5, y: 0.5 }, -5000);
    flushes[1]!();
    expect(sent[1]).toEqual({ t: "wheel", x: 0.5, y: 0.5, dy: -2000 });
  });
});

describe("textMessages", () => {
  it("splits long text into 256-character messages without breaking emoji", () => {
    expect(textMessages("hi")).toEqual([{ t: "text", s: "hi" }]);
    const long = "a".repeat(MAX_TEXT - 1) + "😀" + "b";
    const messages = textMessages(long);
    expect(messages).toHaveLength(2);
    expect(messages[0]).toEqual({ t: "text", s: "a".repeat(MAX_TEXT - 1) });
    expect(messages[1]).toEqual({ t: "text", s: "😀b" });
    expect(textMessages("")).toEqual([]);
  });
});

describe("phoneMessageSchema", () => {
  it("accepts size and control messages only", () => {
    expect(phoneMessageSchema.safeParse({ t: "size", w: 1080, h: 2400, rotation: 0 }).success).toBe(true);
    expect(phoneMessageSchema.safeParse({ t: "control", available: false }).success).toBe(true);
    expect(phoneMessageSchema.safeParse({ t: "down", x: 0, y: 0 }).success).toBe(false);
    expect(phoneMessageSchema.safeParse({ t: "size", w: 0, h: 10, rotation: 0 }).success).toBe(false);
  });
});
