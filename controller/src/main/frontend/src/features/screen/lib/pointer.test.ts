import { describe, expect, it } from "vitest";
import { toNormalized } from "./pointer";

describe("toNormalized", () => {
  // A portrait 1080×2400 phone frame in a 1000×500 element: drawn 225 px wide, centred (bars of 387.5 px).
  const box = { left: 100, top: 50, width: 1000, height: 500 };
  const portrait = { width: 1080, height: 2400 };

  it("maps points on the picture to fractions of the frame", () => {
    expect(toNormalized({ x: 600, y: 300 }, box, portrait)).toEqual({ x: 0.5, y: 0.5 });
    expect(toNormalized({ x: 487.5, y: 50 }, box, portrait)).toEqual({ x: 0, y: 0 });
    expect(toNormalized({ x: 712.5, y: 550 }, box, portrait)).toEqual({ x: 1, y: 1 });
    expect(toNormalized({ x: 532.5, y: 175 }, box, portrait)).toEqual({ x: 0.2, y: 0.25 });
  });

  it("ignores the letterbox bars unless clamping", () => {
    expect(toNormalized({ x: 200, y: 300 }, box, portrait)).toBeNull();
    expect(toNormalized({ x: 200, y: 300 }, box, portrait, true)).toEqual({ x: 0, y: 0.5 });
    expect(toNormalized({ x: 900, y: 600 }, box, portrait, true)).toEqual({ x: 1, y: 1 });
  });

  it("follows the frame when the phone rotates to landscape", () => {
    const landscape = { width: 2400, height: 1080 };
    // Drawn 1000×450, bars of 25 px above and below.
    expect(toNormalized({ x: 100, y: 75 }, box, landscape)).toEqual({ x: 0, y: 0 });
    expect(toNormalized({ x: 600, y: 60 }, box, landscape)).toBeNull();
    expect(toNormalized({ x: 350, y: 300 }, box, landscape)).toEqual({ x: 0.25, y: 0.5 });
  });

  it("returns null before the video has a size", () => {
    expect(toNormalized({ x: 600, y: 300 }, box, { width: 0, height: 0 })).toBeNull();
  });
});
