import { Link as RouterLink } from "@tanstack/react-router";
import { LogOut, Smartphone } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { cn } from "@/shared/lib/utils";
import type { ConnectionState } from "@/shared/ws/client";

type AppShellProps = {
  userName: string;
  connection: ConnectionState;
  onLogout: () => void;
  children: ReactNode;
};

const connectionLabels: Record<ConnectionState, string> = {
  idle: "Not connected",
  connecting: "Connecting…",
  open: "Live",
  closed: "Reconnecting…",
};

export function AppShell({ userName, connection, onLogout, children }: AppShellProps) {
  return (
    <div className="min-h-screen">
      <header className="border-b border-neutral-200 bg-white dark:border-neutral-800 dark:bg-neutral-900">
        <div className="mx-auto flex h-14 max-w-4xl items-center gap-4 px-4">
          <RouterLink to="/devices" className="flex items-center gap-2 font-semibold">
            <Smartphone aria-hidden className="h-5 w-5 text-blue-600" />
            dcontrol
          </RouterLink>
          <span
            className="flex items-center gap-1.5 text-xs text-neutral-500"
            title="Real-time connection to the controller"
          >
            <span
              aria-hidden
              className={cn(
                "h-2 w-2 rounded-full",
                connection === "open" ? "bg-green-500" : "animate-pulse bg-amber-500",
              )}
            />
            {connectionLabels[connection]}
          </span>
          <div className="ml-auto flex items-center gap-2">
            <span className="hidden text-sm text-neutral-600 sm:inline dark:text-neutral-400">{userName}</span>
            <Button variant="quiet" aria-label="Sign out" onPress={onLogout}>
              <LogOut aria-hidden className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-4xl px-4 py-8">{children}</main>
    </div>
  );
}
