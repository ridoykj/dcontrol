import { createFileRoute } from "@tanstack/react-router";
import { LoginPage } from "@/features/auth/pages/LoginPage";
import { loginSearchSchema } from "@/features/auth/schemas/authSchemas";

export const Route = createFileRoute("/login")({
  // Declared optional so plain <Link to="/login"> needs no search params.
  validateSearch: (search: Record<string, unknown>): { redirect?: string } => loginSearchSchema.parse(search),
  component: function LoginRoute() {
    const { redirect } = Route.useSearch();
    return <LoginPage redirectTo={redirect} />;
  },
});
