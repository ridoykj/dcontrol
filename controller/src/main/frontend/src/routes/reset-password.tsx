import { createFileRoute } from "@tanstack/react-router";
import { ResetPasswordPage } from "@/features/auth/pages/ResetPasswordPage";
import { tokenSearchSchema } from "@/features/auth/schemas/authSchemas";

export const Route = createFileRoute("/reset-password")({
  validateSearch: (search) => tokenSearchSchema.parse(search),
  component: function ResetPasswordRoute() {
    const { token } = Route.useSearch();
    return <ResetPasswordPage token={token} />;
  },
});
