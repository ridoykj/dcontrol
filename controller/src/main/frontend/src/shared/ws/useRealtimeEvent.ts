import { useEffect, useRef } from "react";
import type { z } from "zod";
import { useRealtime } from "./RealtimeProvider";

/**
 * Calls {@code handler} for every pushed message of {@code type} whose payload matches {@code schema}.
 * Payloads that don't match are ignored; the controller already validated what phones sent.
 */
export function useRealtimeEvent<T>(type: string, schema: z.ZodType<T>, handler: (payload: T) => void) {
  const { client } = useRealtime();
  const latest = useRef(handler);
  latest.current = handler;

  useEffect(
    () =>
      client.subscribe((message) => {
        if (message.type !== type) return;
        const parsed = schema.safeParse(message.payload);
        if (parsed.success) latest.current(parsed.data);
      }),
    [client, type, schema],
  );
}
