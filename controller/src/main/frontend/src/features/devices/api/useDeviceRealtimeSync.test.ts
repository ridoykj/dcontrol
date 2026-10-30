import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import type { Device } from "../schemas/deviceSchemas";
import { deviceKeys } from "./devicesApi";
import { applyDeviceMessage } from "./useDeviceRealtimeSync";

const device: Device = {
  id: "d1",
  name: "Pixel",
  manufacturer: "Google",
  model: "Pixel 9",
  androidVersion: "17",
  sdkInt: 37,
  appVersion: "1.0.0",
  state: "ACTIVE",
  active: true,
  pairedAt: "2026-10-29T10:00:00Z",
  modifiedAt: "2026-10-29T10:00:00Z",
  status: { deviceId: "d1", name: "Pixel", online: false, battery: null, lastSeen: null },
};

describe("applyDeviceMessage", () => {
  it("patches list and detail caches from device.status", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(deviceKeys.list(false), [device]);
    queryClient.setQueryData(deviceKeys.detail("d1"), device);

    applyDeviceMessage(queryClient, {
      v: 1,
      type: "device.status",
      payload: {
        deviceId: "d1",
        name: "Pixel",
        online: true,
        battery: { level: 64, charging: true },
        lastSeen: "2026-10-29T10:05:00Z",
      },
    });

    expect(queryClient.getQueryData<Device[]>(deviceKeys.list(false))![0]!.status.online).toBe(true);
    expect(queryClient.getQueryData<Device>(deviceKeys.detail("d1"))!.status.battery).toEqual({
      level: 64,
      charging: true,
    });
  });

  it("invalidates lists for unknown devices and ignores malformed payloads", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(deviceKeys.list(false), [device]);

    applyDeviceMessage(queryClient, { v: 1, type: "device.status", payload: { deviceId: 5 } });
    expect(queryClient.getQueryState(deviceKeys.list(false))!.isInvalidated).toBe(false);

    applyDeviceMessage(queryClient, {
      v: 1,
      type: "device.status",
      payload: { deviceId: "d2", name: "New", online: true, battery: null, lastSeen: null },
    });
    expect(queryClient.getQueryState(deviceKeys.list(false))!.isInvalidated).toBe(true);
  });
});
