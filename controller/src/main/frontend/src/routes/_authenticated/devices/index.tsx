import { createFileRoute } from "@tanstack/react-router";
import { DevicesPage } from "@/features/devices/pages/DevicesPage";

export const Route = createFileRoute("/_authenticated/devices/")({
  component: DevicesPage,
});
