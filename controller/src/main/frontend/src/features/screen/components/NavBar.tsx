import { ArrowLeft, Bell, House, LayoutGrid, Lock, Power } from "lucide-react";
import type { ComponentType } from "react";
import { Button } from "@/shared/components/ui/shadcn/Button";
import type { NavKey } from "../lib/controlMessages";

const buttons: { key: NavKey; label: string; icon: ComponentType<{ className?: string; "aria-hidden"?: boolean }> }[] = [
  { key: "back", label: "Back", icon: ArrowLeft },
  { key: "home", label: "Home", icon: House },
  { key: "recents", label: "Recent apps", icon: LayoutGrid },
  { key: "notifications", label: "Notifications", icon: Bell },
  { key: "power", label: "Power menu", icon: Power },
  { key: "lock", label: "Lock screen", icon: Lock },
];

/** The phone's system buttons, sent as Accessibility global actions. */
export function NavBar({ isDisabled, onKey }: { isDisabled: boolean; onKey: (key: NavKey) => void }) {
  return (
    <div role="toolbar" aria-label="Phone buttons" className="flex flex-wrap gap-2">
      {buttons.map(({ key, label, icon: Icon }) => (
        <Button key={key} variant="secondary" aria-label={label} isDisabled={isDisabled} onPress={() => onKey(key)}>
          <Icon aria-hidden className="h-4 w-4" />
          <span className="hidden sm:inline">{label}</span>
        </Button>
      ))}
    </div>
  );
}
