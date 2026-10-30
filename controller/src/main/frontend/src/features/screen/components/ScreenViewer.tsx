import { useEffect, useMemo, useRef } from "react";
import type { KeyboardEvent, PointerEvent, WheelEvent, ClipboardEvent } from "react";
import { cn } from "@/shared/lib/utils";
import { type ControlMessage, createPointerEncoder, createWheelEncoder, textMessages } from "../lib/controlMessages";
import { type Point, toNormalized } from "../lib/pointer";

type ScreenViewerProps = {
  stream: MediaStream | null;
  muted: boolean;
  /** Input reaches the phone only while this is true. */
  controllable: boolean;
  /** Typing goes to the phone's focused text field while the picture has focus. */
  keyboard: boolean;
  onControl: (message: ControlMessage) => void;
};

/** The phone's screen, with mouse, wheel and keyboard input mapped onto the picture. */
export function ScreenViewer({ stream, muted, controllable, keyboard, onControl }: ScreenViewerProps) {
  const video = useRef<HTMLVideoElement>(null);
  const send = useRef(onControl);
  send.current = onControl;

  useEffect(() => {
    if (video.current) video.current.srcObject = stream;
  }, [stream]);

  const pointer = useMemo(() => createPointerEncoder((message) => send.current(message), () => performance.now()), []);
  const wheel = useMemo(
    () => createWheelEncoder((message) => send.current(message), (flush, delay) => setTimeout(flush, delay)),
    [],
  );

  const locate = (event: { clientX: number; clientY: number }, clamp = false): Point | null => {
    const element = video.current;
    if (!element || !element.videoWidth) return null;
    const rect = element.getBoundingClientRect();
    return toNormalized(
      { x: event.clientX, y: event.clientY },
      { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
      { width: element.videoWidth, height: element.videoHeight },
      clamp,
    );
  };

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    event.currentTarget.focus();
    if (!controllable || !event.isPrimary) return;
    if (event.button === 2) {
      send.current({ t: "key", k: "back" });
      return;
    }
    if (event.button !== 0) return;
    const point = locate(event);
    if (!point) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    pointer.down(point);
  };

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!pointer.isDown) return;
    const point = locate(event, true);
    if (point) pointer.move(point);
  };

  const onPointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (!pointer.isDown) return;
    const point = locate(event, true);
    if (point) pointer.up(point);
  };

  const onWheel = (event: WheelEvent<HTMLDivElement>) => {
    if (!controllable) return;
    const point = locate(event);
    if (point) wheel(point, event.deltaMode === 1 ? event.deltaY * 40 : event.deltaY);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!controllable || !keyboard || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.key === "Backspace") send.current({ t: "edit", k: "backspace" });
    else if (event.key === "Enter") send.current({ t: "edit", k: "enter" });
    else if (event.key === "Escape") send.current({ t: "key", k: "back" });
    else if (event.key.length === 1 || [...event.key].length === 1) textMessages(event.key).forEach(send.current);
    else return;
    event.preventDefault();
  };

  const onPaste = (event: ClipboardEvent<HTMLDivElement>) => {
    if (!controllable || !keyboard) return;
    const text = event.clipboardData.getData("text/plain");
    if (!text) return;
    event.preventDefault();
    textMessages(text).forEach(send.current);
  };

  return (
    <div className="relative flex h-[70vh] min-h-80 items-center justify-center overflow-hidden rounded-lg bg-black">
      <video ref={video} autoPlay playsInline muted={muted} className="h-full w-full object-contain" />
      <div
        role="application"
        aria-label={
          controllable
            ? "Phone screen. Click to tap, drag to swipe, scroll to scroll, right-click for Back."
            : "Phone screen (view only)"
        }
        tabIndex={0}
        className={cn(
          "absolute inset-0 touch-none outline-none focus-visible:ring-2 focus-visible:ring-blue-500",
          controllable && "cursor-pointer",
        )}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onWheel={onWheel}
        onKeyDown={onKeyDown}
        onPaste={onPaste}
        onContextMenu={(event) => event.preventDefault()}
      />
    </div>
  );
}
