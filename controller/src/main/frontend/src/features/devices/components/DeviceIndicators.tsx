import { BatteryCharging, BatteryFull, BatteryLow, BatteryMedium, BatteryWarning } from "lucide-react";
import { cn, formatRelativeTime } from "@/shared/lib/utils";
import type { Battery, DeviceStatus } from "../schemas/deviceSchemas";

export function OnlineBadge({ online }: { online: boolean }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium",
        online
          ? "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300"
          : "bg-neutral-200 text-neutral-700 dark:bg-neutral-800 dark:text-neutral-300",
      )}
    >
      <span aria-hidden className={cn("h-2 w-2 rounded-full", online ? "bg-green-500" : "bg-neutral-400")} />
      {online ? "Online" : "Offline"}
    </span>
  );
}

function batteryIcon(battery: Battery) {
  if (battery.charging) return BatteryCharging;
  if (battery.level <= 10) return BatteryWarning;
  if (battery.level <= 30) return BatteryLow;
  if (battery.level <= 70) return BatteryMedium;
  return BatteryFull;
}

export function BatteryIndicator({ battery }: { battery: Battery | null }) {
  if (!battery) {
    return <span className="text-sm text-neutral-500">Battery unknown</span>;
  }
  const Icon = batteryIcon(battery);
  const low = battery.level <= 15 && !battery.charging;
  return (
    <span
      className={cn("inline-flex items-center gap-1 text-sm", low && "text-red-600 dark:text-red-400")}
      aria-label={`Battery ${battery.level}%${battery.charging ? ", charging" : ""}`}
    >
      <Icon aria-hidden className="h-4 w-4" />
      {battery.level}%
    </span>
  );
}

export function LastSeen({ status }: { status: DeviceStatus }) {
  if (status.online) return null;
  return (
    <span className="text-xs text-neutral-500">
      {status.lastSeen ? `Last seen ${formatRelativeTime(status.lastSeen)}` : "Never connected"}
    </span>
  );
}
