import { createFileRoute, Outlet, redirect, useNavigate } from "@tanstack/react-router";
import { logout } from "@/features/auth/api/authApi";
import { useDeviceRealtimeSync } from "@/features/devices/api/useDeviceRealtimeSync";
import { api } from "@/shared/api/client";
import { sessionStore, useSession } from "@/shared/api/session";
import { AppShell } from "@/shared/components/AppShell";
import { useRealtime } from "@/shared/ws/RealtimeProvider";

export const Route = createFileRoute("/_authenticated")({
  beforeLoad: async ({ location }) => {
    if (sessionStore.get().accessToken === null && !(await api.refresh())) {
      throw redirect({ to: "/login", search: { redirect: location.href } });
    }
  },
  component: AuthenticatedLayout,
});

function AuthenticatedLayout() {
  const session = useSession();
  const navigate = useNavigate();
  const { state } = useRealtime();
  useDeviceRealtimeSync();

  return (
    <AppShell
      userName={session.user?.displayName ?? ""}
      connection={state}
      onLogout={() => {
        void logout().finally(() => navigate({ to: "/login" }));
      }}
    >
      <Outlet />
    </AppShell>
  );
}
