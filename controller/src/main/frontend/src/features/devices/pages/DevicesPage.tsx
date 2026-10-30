import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link as RouterLink } from "@tanstack/react-router";
import { Plus, Smartphone } from "lucide-react";
import { useState } from "react";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Switch } from "@/shared/components/ui/shadcn/Switch";
import { deviceKeys, listDevices, restoreDevice } from "../api/devicesApi";
import { BatteryIndicator, LastSeen, OnlineBadge } from "../components/DeviceIndicators";
import type { Device } from "../schemas/deviceSchemas";

function DeviceRow({ device }: { device: Device }) {
  return (
    <RouterLink
      to="/devices/$deviceId"
      params={{ deviceId: device.id }}
      className="flex items-center gap-4 rounded-lg px-3 py-3 outline-blue-600 hover:bg-neutral-100 focus-visible:outline-2 dark:hover:bg-neutral-800"
    >
      <Smartphone aria-hidden className="h-8 w-8 shrink-0 text-neutral-400" />
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium">{device.name}</div>
        <div className="truncate text-xs text-neutral-500">
          {device.manufacturer} {device.model} · Android {device.androidVersion}
        </div>
      </div>
      <div className="flex flex-col items-end gap-1">
        <OnlineBadge online={device.status.online} />
        <div className="flex items-center gap-3">
          <LastSeen status={device.status} />
          <BatteryIndicator battery={device.status.battery} />
        </div>
      </div>
    </RouterLink>
  );
}

function UnpairedRow({ device }: { device: Device }) {
  const queryClient = useQueryClient();
  const restore = useMutation({
    mutationFn: () => restoreDevice(device.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: deviceKeys.all }),
  });
  return (
    <div className="flex items-center gap-4 px-3 py-3">
      <Smartphone aria-hidden className="h-8 w-8 shrink-0 text-neutral-300" />
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium text-neutral-500">{device.name}</div>
        {restore.isError && <div className="text-xs text-red-600">{errorMessage(restore.error)}</div>}
      </div>
      <Button variant="secondary" isPending={restore.isPending} onPress={() => restore.mutate()}>
        Restore
      </Button>
    </div>
  );
}

export function DevicesPage() {
  const [showUnpaired, setShowUnpaired] = useState(false);
  const devices = useQuery({ queryKey: deviceKeys.list(false), queryFn: () => listDevices(false) });
  const unpaired = useQuery({
    queryKey: deviceKeys.list(true),
    queryFn: () => listDevices(true),
    enabled: showUnpaired,
  });

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Devices</h1>
        <RouterLink
          to="/devices/pair"
          className="inline-flex h-9 items-center gap-2 rounded-lg bg-blue-600 px-3.5 text-sm text-white hover:bg-blue-700"
        >
          <Plus aria-hidden className="h-4 w-4" />
          Pair a device
        </RouterLink>
      </div>

      <Card className="p-2">
        {devices.isPending && <ProgressBar aria-label="Loading devices" isIndeterminate className="p-3" />}
        {devices.isError && <Alert tone="error">{errorMessage(devices.error)}</Alert>}
        {devices.isSuccess && devices.data.length === 0 && (
          <div className="flex flex-col items-center gap-2 px-4 py-10 text-center text-sm text-neutral-500">
            <Smartphone aria-hidden className="h-10 w-10" />
            No devices yet. Install the dcontrol app on your phone and pair it.
          </div>
        )}
        {devices.isSuccess && (
          <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
            {devices.data.map((device) => (
              <li key={device.id}>
                <DeviceRow device={device} />
              </li>
            ))}
          </ul>
        )}
      </Card>

      <div className="flex flex-col gap-3">
        <Switch isSelected={showUnpaired} onChange={setShowUnpaired}>
          Show unpaired devices
        </Switch>
        {showUnpaired && (
          <Card className="p-2">
            {unpaired.isPending && <ProgressBar aria-label="Loading unpaired devices" isIndeterminate className="p-3" />}
            {unpaired.isSuccess && unpaired.data.length === 0 && (
              <p className="px-3 py-4 text-sm text-neutral-500">No unpaired devices.</p>
            )}
            {unpaired.isSuccess && (
              <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
                {unpaired.data.map((device) => (
                  <li key={device.id}>
                    <UnpairedRow device={device} />
                  </li>
                ))}
              </ul>
            )}
          </Card>
        )}
      </div>
    </div>
  );
}
