import { useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useEffect, useMemo } from "react";
import { errorMessage } from "@/shared/api/problem";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { saveBlob } from "@/shared/lib/saveBlob";
import { cameraKeys, getCameraMedia } from "../api/cameraApi";
import { type Capture, captureFileName } from "../lib/cameraSession";

function describe(capture: Capture): string {
  const time = new Date(capture.capturedAt).toLocaleTimeString();
  return capture.kind === "VIDEO"
    ? `Clip, ${Math.round((capture.durationMs ?? 0) / 1000)} s, ${time}`
    : `Photo, ${capture.width}×${capture.height}, ${time}`;
}

function CaptureItem({ deviceId, capture }: { deviceId: string; capture: Capture }) {
  const media = useQuery({
    queryKey: cameraKeys.media(deviceId, capture.mediaId),
    queryFn: () => getCameraMedia(deviceId, capture.mediaId),
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: false,
  });
  const url = useMemo(() => (media.data ? URL.createObjectURL(media.data) : null), [media.data]);
  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);

  const label = describe(capture);
  return (
    <li className="flex w-48 shrink-0 flex-col gap-2">
      <div className="flex aspect-[4/3] items-center justify-center overflow-hidden rounded-lg bg-neutral-100 dark:bg-neutral-800">
        {url && capture.kind === "PHOTO" && <img src={url} alt={label} className="h-full w-full object-cover" />}
        {url && capture.kind === "VIDEO" && (
          <video src={url} aria-label={label} controls playsInline className="h-full w-full bg-black object-contain" />
        )}
        {media.isPending && <span className="text-xs text-neutral-500">Loading…</span>}
        {media.isError && (
          <span className="p-2 text-center text-xs text-neutral-500">
            {errorMessage(media.error)}
          </span>
        )}
      </div>
      <span className="text-xs text-neutral-600 dark:text-neutral-400">{label}</span>
      <Button
        variant="secondary"
        className="self-start"
        isDisabled={!media.data}
        onPress={() => media.data && saveBlob(media.data, captureFileName(capture))}
      >
        <Download aria-hidden className="h-4 w-4" />
        <span>Download</span>
      </Button>
    </li>
  );
}

/** Photos and clips of this visit, newest first; the controller keeps them for 30 minutes. */
export function CapturesStrip({ deviceId, captures }: { deviceId: string; captures: Capture[] }) {
  if (captures.length === 0) return null;
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-medium">Captures</h2>
      <ul aria-label="Captures" className="flex gap-3 overflow-x-auto pb-2">
        {captures.map((capture) => (
          <CaptureItem key={capture.mediaId} deviceId={deviceId} capture={capture} />
        ))}
      </ul>
      <p className="text-xs text-neutral-500">Captures stay on the server for 30 minutes. Download what you want to keep.</p>
    </section>
  );
}
