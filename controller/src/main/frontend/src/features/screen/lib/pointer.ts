export type Size = { width: number; height: number };
export type Box = { left: number; top: number; width: number; height: number };
export type Point = { x: number; y: number };

const round = (value: number) => Math.round(value * 10_000) / 10_000;

/**
 * Where a point of the element falls on a video frame drawn with {@code object-fit: contain}, as 0..1 fractions
 * of the frame. Outside the picture (on the letterbox bars) it is null, unless {@code clamp} pulls it to the
 * nearest edge, which keeps a drag going when the pointer leaves the picture.
 */
export function toNormalized(point: Point, box: Box, frame: Size, clamp = false): Point | null {
  if (frame.width <= 0 || frame.height <= 0 || box.width <= 0 || box.height <= 0) return null;
  const scale = Math.min(box.width / frame.width, box.height / frame.height);
  const drawnWidth = frame.width * scale;
  const drawnHeight = frame.height * scale;
  const x = (point.x - box.left - (box.width - drawnWidth) / 2) / drawnWidth;
  const y = (point.y - box.top - (box.height - drawnHeight) / 2) / drawnHeight;
  if (clamp) return { x: round(Math.min(1, Math.max(0, x))), y: round(Math.min(1, Math.max(0, y))) };
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return { x: round(x), y: round(y) };
}
