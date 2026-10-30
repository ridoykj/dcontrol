import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { z } from "zod";
import { api } from "@/shared/api/client";
import { useSession } from "@/shared/api/session";
import { RealtimeClient, type ConnectionState } from "./client";

const ticketSchema = z.object({ ticket: z.string(), expiresAt: z.string() });

function socketUrl(ticket: string): string {
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${window.location.host}/ws/web?ticket=${encodeURIComponent(ticket)}`;
}

type RealtimeContextValue = {
  client: RealtimeClient;
  state: ConnectionState;
};

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

/** Keeps one /ws/web connection open while a user is signed in. */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new RealtimeClient({
        getTicket: async () => {
          try {
            const { ticket } = await api.request("/api/ws/tickets", ticketSchema, { method: "POST" });
            return ticket;
          } catch {
            return null;
          }
        },
        url: socketUrl,
      }),
  );
  const [state, setState] = useState<ConnectionState>(client.connectionState);
  const signedIn = useSession().accessToken !== null;

  useEffect(() => client.onStateChange(setState), [client]);

  useEffect(() => {
    if (!signedIn) return;
    client.start();
    return () => client.stop();
  }, [client, signedIn]);

  return <RealtimeContext.Provider value={{ client, state }}>{children}</RealtimeContext.Provider>;
}

export function useRealtime(): RealtimeContextValue {
  const value = useContext(RealtimeContext);
  if (!value) throw new Error("useRealtime must be used inside RealtimeProvider");
  return value;
}

/** Provides an existing client, for tests that drive pages with a fake. */
export function RealtimeClientProvider({
  client,
  state = "open",
  children,
}: {
  client: RealtimeClient;
  state?: ConnectionState;
  children: ReactNode;
}) {
  return <RealtimeContext.Provider value={{ client, state }}>{children}</RealtimeContext.Provider>;
}
