import { useQuery } from "@tanstack/react-query";
import { Circle, Mic, Square, Volume2, VolumeX } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Select, SelectItem } from "@/shared/components/ui/shadcn/Select";
import { StatsLine } from "@/shared/rtc/StatsLine";
import { ClipsList } from "../components/ClipsList";
import { useMicSession } from "../hooks/useMicSession";
import { CLIP_SECONDS, endText } from "../lib/micSession";

/** Plays the live stream so it can be heard; hidden because there is nothing to show. */
function Listener({ stream, muted }: { stream: MediaStream | null; muted: boolean }) {
  const audio = useRef<HTMLAudioElement>(null);
  useEffect(() => {
    if (audio.current) audio.current.srcObject = stream;
  }, [stream]);
  return <audio ref={audio} aria-label="Microphone audio" autoPlay muted={muted} className="hidden" />;
}

/** A live VU meter of the stream's loudness, from the Web Audio time-domain data. */
function LevelMeter({ stream, active }: { stream: MediaStream | null; active: boolean }) {
  const [level, setLevel] = useState(0);
  useEffect(() => {
    if (!stream || !active) {
      setLevel(0);
      return;
    }
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const source = ctx.createMediaStreamSource(stream);
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(analyser);
    const data = new Uint8Array(analyser.fftSize);
    let raf = 0;
    const tick = () => {
      analyser.getByteTimeDomainData(data);
      let sum = 0;
      for (const sample of data) {
        const centred = (sample - 128) / 128;
        sum += centred * centred;
      }
      setLevel(Math.min(1, Math.sqrt(sum / data.length) * 2));
      raf = requestAnimationFrame(tick);
    };
    tick();
    return () => {
      cancelAnimationFrame(raf);
      source.disconnect();
      void ctx.close();
    };
  }, [stream, active]);

  const percent = Math.round(level * 100);
  return (
    <div
      role="meter"
      aria-label="Input level"
      aria-valuenow={percent}
      aria-valuemin={0}
      aria-valuemax={100}
      className="h-3 w-64 max-w-full overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800"
    >
      <div className="h-full rounded-full bg-green-500 transition-[width] duration-75" style={{ width: `${percent}%` }} />
    </div>
  );
}

/** Live listening to the phone's microphone, with short clips, after the owner accepts on the phone. */
export function MicPage({ deviceId }: { deviceId: string }) {
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const { state, stream, stats, failure, clips, capturing, actionError, start, stop, capture } = useMicSession(deviceId);
  const [muted, setMuted] = useState(false);
  const [clipSeconds, setClipSeconds] = useState<number>(30);

  if (!device.isSuccess) return null;
  const running = state.phase !== "idle" && state.phase !== "ended";

  if (!running) {
    const permission = missingPermission(failure);
    return (
      <div className="flex flex-col gap-4">
        <div className="flex max-w-xl flex-col gap-4">
          {!online && <Alert>The phone is offline. The microphone needs the phone to be connected.</Alert>}
          {permission && <PermissionRequired permission={permission} />}
          {state.error && !permission && <Alert tone="error">{state.error}</Alert>}
          {state.endReason && !state.error && (
            <Alert tone={state.endReason === "STOPPED" ? "info" : "error"}>{endText(state.endReason)}</Alert>
          )}
          <Card className="flex flex-col gap-4">
            <h2 className="font-medium">Use the phone's microphone</h2>
            <p className="text-sm text-neutral-600 dark:text-neutral-400">
              Someone has to accept on the phone for every session. The phone shows a notification with{" "}
              <strong>Stop</strong> for as long as the microphone is on, and closing this tab turns it off.
            </p>
            <Button className="self-start" isDisabled={!online} onPress={start}>
              <Mic aria-hidden className="h-4 w-4" />
              <span>Request microphone</span>
            </Button>
          </Card>
        </div>
        <ClipsList deviceId={deviceId} clips={clips} />
      </div>
    );
  }

  const live = state.phase === "live";
  const recording = capturing;
  return (
    <div className="flex flex-col gap-3">
      {state.phase === "requesting" && <ProgressBar aria-label="Asking the phone" isIndeterminate />}
      {state.phase === "awaiting_consent" && <Alert>Waiting for someone to accept on the phone…</Alert>}
      {state.phase === "connecting" && <ProgressBar aria-label="Connecting to the phone" isIndeterminate />}
      {actionError && <Alert tone="error">{actionError}</Alert>}
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="destructive" onPress={stop}>
          <Square aria-hidden className="h-4 w-4" />
          <span>Stop</span>
        </Button>
        <Button variant="secondary" aria-label={muted ? "Unmute" : "Mute"} onPress={() => setMuted(!muted)}>
          {muted ? <VolumeX aria-hidden className="h-4 w-4" /> : <Volume2 aria-hidden className="h-4 w-4" />}
        </Button>
        <Select
          aria-label="Clip length"
          selectedKey={String(clipSeconds)}
          onSelectionChange={(key) => setClipSeconds(Number(key))}
          isDisabled={capturing}
          className="w-24"
        >
          {CLIP_SECONDS.map((seconds) => (
            <SelectItem key={seconds} id={String(seconds)}>
              {`${seconds} s`}
            </SelectItem>
          ))}
        </Select>
        <Button variant="secondary" isDisabled={!live || capturing} onPress={() => void capture(clipSeconds * 1000)}>
          {recording ? (
            <Circle aria-hidden className="h-4 w-4 fill-red-600 text-red-600" />
          ) : (
            <Mic aria-hidden className="h-4 w-4" />
          )}
          <span>{recording ? "Recording…" : "Record clip"}</span>
        </Button>
        {live && <StatsLine stats={stats} video={false} />}
      </div>
      <LevelMeter stream={stream} active={live} />
      <Listener stream={stream} muted={muted} />
      <ClipsList deviceId={deviceId} clips={clips} />
    </div>
  );
}
