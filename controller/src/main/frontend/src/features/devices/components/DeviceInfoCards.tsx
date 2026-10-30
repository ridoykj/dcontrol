import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { formatBytes } from "@/shared/lib/utils";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { deviceInfoSchema, type DeviceInfo } from "../schemas/deviceInfoSchemas";
import { relayErrorMessage } from "./relayErrorMessage";

export const deviceInfoKey = (deviceId: string) => ["devices", "info", deviceId] as const;

function UsageBar({ label, used, total }: { label: string; used: number; total: number }) {
  const percent = total > 0 ? Math.round((used / total) * 100) : 0;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex justify-between text-sm">
        <span>{label}</span>
        <span className="text-neutral-500">
          {formatBytes(used)} of {formatBytes(total)}
        </span>
      </div>
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        className="h-2 overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800"
      >
        <div className="h-full rounded-full bg-blue-600" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function Facts({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card className="flex flex-col gap-3">
      <h2 className="font-medium">{title}</h2>
      <dl className="grid grid-cols-2 gap-3">{children}</dl>
    </Card>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="text-sm break-words">{value}</dd>
    </div>
  );
}

function formatUptime(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  return days > 0 ? `${days} d ${hours} h` : `${hours} h ${minutes % 60} min`;
}

const healthLabels: Record<DeviceInfo["battery"]["health"], string> = {
  GOOD: "Good",
  OVERHEAT: "Overheating",
  DEAD: "Dead",
  OVER_VOLTAGE: "Over voltage",
  COLD: "Cold",
  FAILURE: "Failure",
  UNKNOWN: "Unknown",
};

export function DeviceInfoView({ info }: { info: DeviceInfo }) {
  const usedMemory = info.memory.totalBytes - info.memory.availableBytes;
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card className="flex flex-col gap-4">
        <h2 className="font-medium">Storage and memory</h2>
        {info.storage.map((volume) => (
          <UsageBar
            key={volume.id}
            label={volume.label}
            used={volume.totalBytes - volume.freeBytes}
            total={volume.totalBytes}
          />
        ))}
        <UsageBar label="Memory" used={usedMemory} total={info.memory.totalBytes} />
      </Card>
      <Facts title="Battery">
        <Fact label="Level" value={`${info.battery.level}%${info.battery.charging ? " (charging)" : ""}`} />
        <Fact label="Health" value={healthLabels[info.battery.health]} />
        <Fact label="Temperature" value={`${info.battery.temperatureC.toFixed(1)} °C`} />
        <Fact label="Voltage" value={`${(info.battery.voltageMv / 1000).toFixed(2)} V`} />
        <Fact label="Power source" value={info.battery.plugged === "NONE" ? "Battery" : info.battery.plugged} />
      </Facts>
      <Facts title="System">
        <Fact label="Android" value={`${info.os.androidVersion} (API ${info.os.sdkInt})`} />
        <Fact label="Security patch" value={info.os.securityPatch ?? "Unknown"} />
        <Fact label="Build" value={info.os.buildId} />
        <Fact label="CPU" value={`${info.cpuCores} cores, ${info.os.abis.join(", ")}`} />
        <Fact label="Uptime" value={formatUptime(info.uptimeMs)} />
        <Fact label="Locale and time zone" value={`${info.locale}, ${info.timeZone}`} />
      </Facts>
      <Facts title="Display and network">
        <Fact label="Resolution" value={`${info.display.widthPx} × ${info.display.heightPx}`} />
        <Fact
          label="Density and refresh"
          value={`${info.display.densityDpi} dpi, ${Math.round(info.display.refreshRateHz)} Hz`}
        />
        <Fact label="Network" value={`${info.network.type}${info.network.metered ? " (metered)" : ""}`} />
        <Fact label="IP addresses" value={info.network.ipAddresses.join(", ") || "None"} />
      </Facts>
    </div>
  );
}

/** Live device details, read from the phone on demand (nothing is stored on the controller). */
export function DeviceInfoCards({ deviceId, online }: { deviceId: string; online: boolean }) {
  const { client } = useRealtime();
  const info = useQuery({
    queryKey: deviceInfoKey(deviceId),
    queryFn: async () => deviceInfoSchema.parse((await client.request("device.info", { deviceId })).payload),
    enabled: online,
    retry: false,
    staleTime: 60_000,
  });

  return (
    <section className="flex flex-col gap-3" aria-label="Device details">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Details</h2>
        <Button
          variant="secondary"
          isDisabled={!online}
          isPending={info.isFetching}
          onPress={() => void info.refetch()}
        >
          <RefreshCw aria-hidden className="h-4 w-4" />
          <span>Refresh</span>
        </Button>
      </div>
      {!online && !info.data && <Alert>Details are read live from the phone. Connect it to see them.</Alert>}
      {online && info.isPending && <ProgressBar aria-label="Reading device details" isIndeterminate />}
      {info.isError && <Alert tone="error">{relayErrorMessage(info.error)}</Alert>}
      {info.data && <DeviceInfoView info={info.data} />}
    </section>
  );
}
