import { useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useEffect, useMemo } from "react";
import { errorMessage } from "@/shared/api/problem";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { saveBlob } from "@/shared/lib/saveBlob";
import { getMicMedia, micKeys } from "../api/micApi";
import { type Capture, captureFileName } from "../lib/micSession";

function describe(clip: Capture): string {
  const time = new Date(clip.capturedAt).toLocaleTimeString();
  return `Clip, ${Math.round((clip.durationMs ?? 0) / 1000)} s, ${time}`;
}

function ClipItem({ deviceId, clip }: { deviceId: string; clip: Capture }) {
  const media = useQuery({
    queryKey: micKeys.media(deviceId, clip.mediaId),
    queryFn: () => getMicMedia(deviceId, clip.mediaId),
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: false,
  });
  const url = useMemo(() => (media.data ? URL.createObjectURL(media.data) : null), [media.data]);
  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);

  const label = describe(clip);
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-neutral-200 p-3 dark:border-neutral-800">
      <span className="text-sm text-neutral-600 dark:text-neutral-400">{label}</span>
      {url && <audio src={url} aria-label={label} controls className="w-full" />}
      {media.isPending && <span className="text-xs text-neutral-500">Loading…</span>}
      {media.isError && <span className="text-xs text-neutral-500">{errorMessage(media.error)}</span>}
      <Button
        variant="secondary"
        className="self-start"
        isDisabled={!media.data}
        onPress={() => media.data && saveBlob(media.data, captureFileName(clip))}
      >
        <Download aria-hidden className="h-4 w-4" />
        <span>Download</span>
      </Button>
    </li>
  );
}

/** Clips of this visit, newest first; the controller keeps them for 30 minutes. */
export function ClipsList({ deviceId, clips }: { deviceId: string; clips: Capture[] }) {
  if (clips.length === 0) return null;
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-medium">Clips</h2>
      <ul aria-label="Clips" className="flex max-w-xl flex-col gap-3">
        {clips.map((clip) => (
          <ClipItem key={clip.mediaId} deviceId={deviceId} clip={clip} />
        ))}
      </ul>
      <p className="text-xs text-neutral-500">Clips stay on the server for 30 minutes. Download what you want to keep.</p>
    </section>
  );
}
