import { useInfiniteQuery, type QueryKey } from "@tanstack/react-query";
import type { z } from "zod";
import { useRealtime } from "./RealtimeProvider";

type Paged = { nextCursor?: string | null };

type RelayedPagesOptions<T extends Paged> = {
  queryKey: QueryKey;
  /** Relayed command type, e.g. {@code contacts.list}. */
  type: string;
  /** Command payload without {@code cursor}; must include {@code deviceId}. */
  payload: Record<string, unknown>;
  schema: z.ZodType<T>;
  enabled: boolean;
};

/**
 * Pages of a phone list read live through the relay (ws-v1.md, Phase 3 cursors). Nothing is cached on the
 * controller, so a refetch asks the phone again; relay errors are not retried.
 */
export function useRelayedPages<T extends Paged>({ queryKey, type, payload, schema, enabled }: RelayedPagesOptions<T>) {
  const { client } = useRealtime();
  return useInfiniteQuery({
    queryKey,
    enabled,
    retry: false,
    staleTime: 30_000,
    initialPageParam: null as string | null,
    queryFn: async ({ pageParam }) => schema.parse((await client.request(type, { ...payload, cursor: pageParam })).payload),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}
