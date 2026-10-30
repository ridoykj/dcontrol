import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { useRealtime } from "@/shared/ws/RealtimeProvider";

export const simSchema = z.object({
  id: z.number(),
  label: z.string(),
  slot: z.number(),
  carrier: z.string().nullish(),
});

export const simsSchema = z.object({
  hasTelephony: z.boolean(),
  access: z.enum(["GRANTED", "MISSING"]),
  defaultSmsSimId: z.number().nullish(),
  defaultCallSimId: z.number().nullish(),
  sims: z.array(simSchema),
});

export type Sim = z.infer<typeof simSchema>;
export type Sims = z.infer<typeof simsSchema>;

/** The phone's active SIMs (empty without READ_PHONE_STATE; the phone then uses its default SIM). */
export function useSims(deviceId: string, online: boolean) {
  const { client } = useRealtime();
  return useQuery({
    queryKey: ["phone", deviceId, "sims"],
    enabled: online,
    retry: false,
    staleTime: 5 * 60_000,
    queryFn: async () => simsSchema.parse((await client.request("telephony.sims", { deviceId })).payload),
  });
}
