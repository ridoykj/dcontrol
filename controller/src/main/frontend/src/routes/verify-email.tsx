import { createFileRoute } from "@tanstack/react-router";
import { VerifyEmailPage } from "@/features/auth/pages/VerifyEmailPage";
import { tokenSearchSchema } from "@/features/auth/schemas/authSchemas";

export const Route = createFileRoute("/verify-email")({
  validateSearch: (search) => tokenSearchSchema.parse(search),
  component: function VerifyEmailRoute() {
    const { token } = Route.useSearch();
    return <VerifyEmailPage token={token} />;
  },
});
