import { useQuery } from "@tanstack/react-query";
import { Camera, Circle, Maximize, SwitchCamera, Square, Video, Volume2, VolumeX } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Select, SelectItem } from "@/shared/components/ui/shadcn/Select";
import { Switch } from "@/shared/components/ui/shadcn/Switch";
import { StatsLine } from "@/shared/rtc/StatsLine";
import { CapturesStrip } from "../components/CapturesStrip";
import { useCameraSession } from "../hooks/useCameraSession";
import { CLIP_SECONDS, endText, type Facing, FACINGS, facingLabels } from "../lib/cameraSession";

function Viewfinder({ stream, muted }: { stream: MediaStream | null; muted: boolean }) {
  const video = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    if (video.current) video.current.srcObject = stream;
  }, [stream]);
  return (
    <video
      ref={video}
      aria-label="Camera view"
      autoPlay
      playsInline
      muted={muted}
      className="max-h-[70vh] w-full rounded-lg bg-black object-contain"
    />
  );
}

/** Live view of the phone's camera, with photos and short clips, after the owner accepts on the phone. */
export function CameraPage({ deviceId }: { deviceId: string }) {
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const { state, stream, stats, failure, captures, capturing, switching, actionError, start, stop, capture, switchCamera } =
    useCameraSession(deviceId);
  const [facing, setFacing] = useState<Facing>("BACK");
  const [audio, setAudio] = useState(false);
  const [muted, setMuted] = useState(false);
  const [clipSeconds, setClipSeconds] = useState<number>(10);
  const frame = useRef<HTMLDivElement>(null);

  if (!device.isSuccess) return null;
  const running = state.phase !== "idle" && state.phase !== "ended";

  if (!running) {
    const permission = missingPermission(failure);
    return (
      <div className="flex flex-col gap-4">
        <div className="flex max-w-xl flex-col gap-4">
          {!online && <Alert>The phone is offline. The camera needs the phone to be connected.</Alert>}
          {permission && <PermissionRequired permission={permission} />}
          {state.error && !permission && <Alert tone="error">{state.error}</Alert>}
          {state.endReason && !state.error && (
            <Alert tone={state.endReason === "STOPPED" ? "info" : "error"}>{endText(state.endReason)}</Alert>
          )}
          <Card className="flex flex-col gap-4">
            <h2 className="font-medium">Use the phone's camera</h2>
            <p className="text-sm text-neutral-600 dark:text-neutral-400">
              Someone has to accept on the phone for every session. The phone shows a notification with{" "}
              <strong>Stop</strong> for as long as the camera is on, and closing this tab turns it off.
            </p>
            <Select label="Camera" selectedKey={facing} onSelectionChange={(key) => setFacing(key as Facing)}>
              {FACINGS.map((candidate) => (
                <SelectItem key={candidate} id={candidate}>
                  {facingLabels[candidate]}
                </SelectItem>
              ))}
            </Select>
            <Switch isSelected={audio} onChange={setAudio}>
              Microphone (needs the microphone permission on the phone)
            </Switch>
            <Button className="self-start" isDisabled={!online} onPress={() => start({ facing, audio })}>
              <Camera aria-hidden className="h-4 w-4" />
              <span>Request camera</span>
            </Button>
          </Card>
        </div>
        <CapturesStrip deviceId={deviceId} captures={captures} />
      </div>
    );
  }

  const live = state.phase === "live";
  const recording = capturing === "VIDEO";
  const other: Facing = state.facing === "FRONT" ? "BACK" : "FRONT";
  return (
    <div className="flex flex-col gap-3">
      {state.phase === "requesting" && <ProgressBar aria-label="Asking the phone" isIndeterminate />}
      {state.phase === "awaiting_consent" && <Alert>Waiting for someone to accept on the phone…</Alert>}
      {state.phase === "connecting" && <ProgressBar aria-label="Connecting to the phone" isIndeterminate />}
      {live && audio && !state.audio && (
        <Alert>The phone hasn't allowed dcontrol to use the microphone, so there is no sound.</Alert>
      )}
      {actionError && <Alert tone="error">{actionError}</Alert>}
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="destructive" onPress={stop}>
          <Square aria-hidden className="h-4 w-4" />
          <span>Stop</span>
        </Button>
        <Button
          variant="secondary"
          isDisabled={!live || recording || switching}
          onPress={() => void switchCamera(other)}
        >
          <SwitchCamera aria-hidden className="h-4 w-4" />
          <span>Switch camera</span>
        </Button>
        <Button variant="secondary" isDisabled={!live || capturing !== null} onPress={() => void capture("PHOTO")}>
          <Camera aria-hidden className="h-4 w-4" />
          <span>{capturing === "PHOTO" ? "Taking photo…" : "Take photo"}</span>
        </Button>
        {state.video && (
          <>
            <Select
              aria-label="Clip length"
              selectedKey={String(clipSeconds)}
              onSelectionChange={(key) => setClipSeconds(Number(key))}
              isDisabled={capturing !== null}
              className="w-24"
            >
              {CLIP_SECONDS.map((seconds) => (
                <SelectItem key={seconds} id={String(seconds)}>
                  {`${seconds} s`}
                </SelectItem>
              ))}
            </Select>
            <Button
              variant="secondary"
              isDisabled={!live || capturing !== null}
              onPress={() => void capture("VIDEO", clipSeconds * 1000)}
            >
              {recording ? (
                <Circle aria-hidden className="h-4 w-4 fill-red-600 text-red-600" />
              ) : (
                <Video aria-hidden className="h-4 w-4" />
              )}
              <span>{recording ? "Recording…" : "Record clip"}</span>
            </Button>
          </>
        )}
        {state.audio && (
          <Button variant="secondary" aria-label={muted ? "Unmute" : "Mute"} onPress={() => setMuted(!muted)}>
            {muted ? <VolumeX aria-hidden className="h-4 w-4" /> : <Volume2 aria-hidden className="h-4 w-4" />}
          </Button>
        )}
        <Button
          variant="secondary"
          aria-label="Full screen"
          onPress={() => void frame.current?.requestFullscreen?.()}
        >
          <Maximize aria-hidden className="h-4 w-4" />
        </Button>
        {live && <StatsLine stats={stats} />}
      </div>
      {live && !state.video && (
        <p className="text-xs text-neutral-500">This phone can't record clips while the camera is shared; photos only.</p>
      )}
      <div ref={frame}>
        <Viewfinder stream={stream} muted={muted} />
      </div>
      <CapturesStrip deviceId={deviceId} captures={captures} />
    </div>
  );
}
