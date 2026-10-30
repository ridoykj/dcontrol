import { Smartphone } from "lucide-react";
import type { ReactNode } from "react";
import { Card } from "@/shared/components/Card";

export function AuthLayout({ title, children, footer }: { title: string; children: ReactNode; footer?: ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center justify-center gap-2 text-lg font-semibold">
          <Smartphone aria-hidden className="h-5 w-5 text-blue-600" />
          dcontrol
        </div>
        <Card>
          <h1 className="mb-5 text-xl font-semibold">{title}</h1>
          {children}
        </Card>
        {footer && <div className="mt-4 text-center text-sm text-neutral-600 dark:text-neutral-400">{footer}</div>}
      </div>
    </main>
  );
}
