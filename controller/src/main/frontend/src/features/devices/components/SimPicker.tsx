import { Select, SelectItem } from "@/shared/components/ui/shadcn/Select";
import type { Sim } from "../api/simsApi";

type SimPickerProps = {
  sims: Sim[];
  /** Null means the phone's default SIM. */
  value: number | null;
  onChange: (simId: number | null) => void;
  label?: string;
};

const DEFAULT = "default";

/** SIM choice; only shown when the phone reports two or more SIMs. */
export function SimPicker({ sims, value, onChange, label = "SIM" }: SimPickerProps) {
  if (sims.length < 2) return null;
  return (
    <Select
      label={label}
      selectedKey={value === null ? DEFAULT : String(value)}
      onSelectionChange={(key) => onChange(key === DEFAULT || key === null ? null : Number(key))}
      className="w-48"
    >
      <SelectItem id={DEFAULT}>Phone default</SelectItem>
      {sims.map((sim) => (
        <SelectItem key={sim.id} id={String(sim.id)}>
          {sim.carrier ? `${sim.label} (${sim.carrier})` : sim.label}
        </SelectItem>
      ))}
    </Select>
  );
}
