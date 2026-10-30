import { useQuery } from "@tanstack/react-query";
import { Outlet, Link as RouterLink, useNavigate, useRouterState } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";
import type { Key } from "react-aria-components";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Tab, TabList, TabPanel, Tabs } from "@/shared/components/ui/shadcn/Tabs";
import { deviceKeys, getDevice } from "../api/devicesApi";
import { BatteryIndicator, LastSeen, OnlineBadge } from "../components/DeviceIndicators";

const SECTIONS = [
  "overview",
  "screen",
  "camera",
  "files",
  "notifications",
  "clipboard",
  "contacts",
  "messages",
  "calls",
  "find",
] as const;
type Section = (typeof SECTIONS)[number];

function currentSection(pathname: string): Section {
  const last = pathname.replace(/\/+$/, "").split("/").pop();
  return SECTIONS.find((section) => section === last) ?? "overview";
}

/** Device header plus a tab per feature; the selected feature renders through the route outlet. */
export function DeviceLayout({ deviceId }: { deviceId: string }) {
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const section = currentSection(pathname);

  const open = (key: Key) => {
    const params = { deviceId };
    switch (key as Section) {
      case "screen":
        void navigate({ to: "/devices/$deviceId/screen", params });
        break;
      case "camera":
        void navigate({ to: "/devices/$deviceId/camera", params });
        break;
      case "find":
        void navigate({ to: "/devices/$deviceId/find", params });
        break;
      case "files":
        void navigate({ to: "/devices/$deviceId/files", params, search: {} });
        break;
      case "notifications":
        void navigate({ to: "/devices/$deviceId/notifications", params });
        break;
      case "clipboard":
        void navigate({ to: "/devices/$deviceId/clipboard", params });
        break;
      case "contacts":
        void navigate({ to: "/devices/$deviceId/contacts", params, search: {} });
        break;
      case "messages":
        void navigate({ to: "/devices/$deviceId/messages", params, search: {} });
        break;
      case "calls":
        void navigate({ to: "/devices/$deviceId/calls", params, search: {} });
        break;
      default:
        void navigate({ to: "/devices/$deviceId", params });
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <RouterLink to="/devices" className="inline-flex items-center gap-1 text-sm text-blue-600 hover:underline">
        <ArrowLeft aria-hidden className="h-4 w-4" />
        All devices
      </RouterLink>
      {device.isPending && <ProgressBar aria-label="Loading device" isIndeterminate />}
      {device.isError && <Alert tone="error">{errorMessage(device.error)}</Alert>}
      {device.isSuccess && (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold">{device.data.name}</h1>
            <OnlineBadge online={device.data.status.online} />
            <BatteryIndicator battery={device.data.status.battery} />
            <LastSeen status={device.data.status} />
          </div>
          <Tabs selectedKey={section} onSelectionChange={open}>
            <TabList aria-label="Device features" className="overflow-x-auto">
              <Tab id="overview">Overview</Tab>
              <Tab id="screen">Screen</Tab>
              <Tab id="camera">Camera</Tab>
              <Tab id="files">Files</Tab>
              <Tab id="notifications">Notifications</Tab>
              <Tab id="clipboard">Clipboard</Tab>
              <Tab id="contacts">Contacts</Tab>
              <Tab id="messages">Messages</Tab>
              <Tab id="calls">Calls</Tab>
              <Tab id="find">Find</Tab>
            </TabList>
            <TabPanel id={section} className="pt-4">
              <Outlet />
            </TabPanel>
          </Tabs>
        </>
      )}
    </div>
  );
}
