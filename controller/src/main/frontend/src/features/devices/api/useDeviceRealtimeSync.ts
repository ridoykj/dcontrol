import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import type { Envelope } from "@/shared/ws/client";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import {
  deviceStatusSchema,
  devicesSnapshotSchema,
  pairingCompletedSchema,
  type Device,
  type DeviceStatus,
} from "../schemas/deviceSchemas";
import { deviceKeys } from "./devicesApi";

function applyStatus(queryClient: QueryClient, status: DeviceStatus) {
  let known = false;
  queryClient.setQueriesData<Device[]>({ queryKey: deviceKeys.lists() }, (devices) =>
    devices?.map((device) => {
      if (device.id !== status.deviceId) return device;
      known = true;
      return { ...device, name: status.name, status };
    }),
  );
  queryClient.setQueryData<Device>(deviceKeys.detail(status.deviceId), (device) =>
    device ? { ...device, name: status.name, status } : device,
  );
  if (!known) {
    void queryClient.invalidateQueries({ queryKey: deviceKeys.lists() });
  }
}

/** Applies pushed device events to the query cache. Mount once inside the signed-in layout. */
export function applyDeviceMessage(queryClient: QueryClient, message: Envelope) {
  switch (message.type) {
    case "device.status": {
      const status = deviceStatusSchema.safeParse(message.payload);
      if (status.success) applyStatus(queryClient, status.data);
      break;
    }
    case "devices.snapshot": {
      const snapshot = devicesSnapshotSchema.safeParse(message.payload);
      if (snapshot.success) snapshot.data.devices.forEach((status) => applyStatus(queryClient, status));
      break;
    }
    case "pairing.completed": {
      if (pairingCompletedSchema.safeParse(message.payload).success) {
        void queryClient.invalidateQueries({ queryKey: deviceKeys.all });
      }
      break;
    }
  }
}

export function useDeviceRealtimeSync() {
  const queryClient = useQueryClient();
  const { client } = useRealtime();
  useEffect(() => client.subscribe((message) => applyDeviceMessage(queryClient, message)), [client, queryClient]);
}
