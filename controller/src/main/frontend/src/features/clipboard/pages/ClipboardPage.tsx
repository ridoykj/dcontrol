import { useQuery } from "@tanstack/react-query";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { ClipboardPanel } from "../components/ClipboardPanel";

export function ClipboardPage({ deviceId }: { deviceId: string }) {
  const { client } = useRealtime();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  if (!device.isSuccess) return null;
  return <ClipboardPanel client={client} deviceId={deviceId} online={device.data.status.online} />;
}
