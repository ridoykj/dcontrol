import { useQuery } from "@tanstack/react-query";
import { Keyboard, Maximize, MonitorSmartphone, Square, Volume2, VolumeX } from "lucide-react";
import { useRef, useState } from "react";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Select, SelectItem } from "@/shared/components/ui/shadcn/Select";
import { Switch } from "@/shared/components/ui/shadcn/Switch";
import { StatsLine } from "@/shared/rtc/StatsLine";
import { NavBar } from "../components/NavBar";
import { ScreenViewer } from "../components/ScreenViewer";
import { useScreenSession } from "../hooks/useScreenSession";
import { endText, type Quality, QUALITIES, qualityLabels } from "../lib/screenSession";

/** Mirror the phone's screen and, with the Accessibility service on, control it. */
export function ScreenPage({ deviceId }: { deviceId: string }) {
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const { state, stream, stats, start, stop, sendControl } = useScreenSession(deviceId);
  const [quality, setQuality] = useState<Quality>("MEDIUM");
  const [audio, setAudio] = useState(true);
  const [control, setControl] = useState(true);
  const [muted, setMuted] = useState(false);
  const [keyboard, setKeyboard] = useState(false);
  const frame = useRef<HTMLDivElement>(null);

  if (!device.isSuccess) return null;
  const running = state.phase !== "idle" && state.phase !== "ended";

  if (!running) {
    return (
      <div className="flex max-w-xl flex-col gap-4">
        {!online && <Alert>The phone is offline. Screen sharing needs the phone to be connected.</Alert>}
        {state.error && <Alert tone="error">{state.error}</Alert>}
        {state.endReason && !state.error && (
          <Alert tone={state.endReason === "STOPPED" ? "info" : "error"}>{endText(state.endReason)}</Alert>
        )}
        <Card className="flex flex-col gap-4">
          <h2 className="font-medium">Share the phone's screen</h2>
          <p className="text-sm text-neutral-600 dark:text-neutral-400">
            Someone has to tap <strong>Start</strong> on the phone for every session; Android doesn't allow
            unattended screen sharing. Banking apps and other protected screens show as black, and Android 15 and
            later hide sensitive notifications while the screen is shared.
          </p>
          <Select label="Quality" selectedKey={quality} onSelectionChange={(key) => setQuality(key as Quality)}>
            {QUALITIES.map((candidate) => (
              <SelectItem key={candidate} id={candidate}>
                {qualityLabels[candidate]}
              </SelectItem>
            ))}
          </Select>
          <Switch isSelected={audio} onChange={setAudio}>
            Phone audio (media and games; never calls)
          </Switch>
          <Switch isSelected={control} onChange={setControl}>
            Remote control (needs the Remote control service on the phone)
          </Switch>
          <Button className="self-start" isDisabled={!online} onPress={() => start({ quality, audio, control })}>
            <MonitorSmartphone aria-hidden className="h-4 w-4" />
            <span>Start screen sharing</span>
          </Button>
        </Card>
      </div>
    );
  }

  const live = state.phase === "live";
  const controllable = live && state.controlAvailable;
  return (
    <div className="flex flex-col gap-3">
      {state.phase === "requesting" && <ProgressBar aria-label="Asking the phone" isIndeterminate />}
      {state.phase === "awaiting_consent" && (
        <Alert>Waiting for someone to tap Start on the phone…</Alert>
      )}
      {state.phase === "connecting" && <ProgressBar aria-label="Connecting to the phone" isIndeterminate />}
      {live && control && !state.control && (
        <Alert>
          Remote control is off: turn on the <strong>Remote control</strong> service in dcontrol on the phone, then
          start again.
        </Alert>
      )}
      {live && state.control && !state.controlAvailable && (
        <Alert>The Remote control service was turned off on the phone. The screen is view-only.</Alert>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="destructive" onPress={stop}>
          <Square aria-hidden className="h-4 w-4" />
          <span>Stop</span>
        </Button>
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
        {state.control && (
          <Switch isSelected={keyboard} onChange={setKeyboard} isDisabled={!controllable}>
            <Keyboard aria-hidden className="h-4 w-4" />
            Type on the phone
          </Switch>
        )}
        {live && <StatsLine stats={stats} />}
      </div>
      {state.control && <NavBar isDisabled={!controllable} onKey={(key) => sendControl({ t: "key", k: key })} />}
      <div ref={frame}>
        <ScreenViewer
          stream={stream}
          muted={muted}
          controllable={controllable}
          keyboard={keyboard}
          onControl={sendControl}
        />
      </div>
      {keyboard && controllable && (
        <p className="text-xs text-neutral-500">
          Click the screen, then type: text goes into the focused field on the phone. Esc is Back. Games and some
          custom keyboards ignore typed text.
        </p>
      )}
    </div>
  );
}
