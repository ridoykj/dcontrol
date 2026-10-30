import { useMutation } from "@tanstack/react-query";
import { Link as RouterLink } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { verifyEmail } from "../api/authApi";
import { AuthLayout } from "../components/AuthLayout";

export function VerifyEmailPage({ token }: { token: string }) {
  const mutation = useMutation({ mutationFn: verifyEmail });
  const started = useRef(false);

  useEffect(() => {
    if (token && !started.current) {
      started.current = true;
      mutation.mutate(token);
    }
  }, [token, mutation]);

  return (
    <AuthLayout title="Verify email">
      <div className="flex flex-col gap-4 text-sm">
        {!token && <Alert tone="error">This link is incomplete. Open the link from the email again.</Alert>}
        {token && mutation.isPending && <ProgressBar aria-label="Verifying" isIndeterminate />}
        {mutation.isSuccess && <Alert tone="success">Your email is verified. You can sign in now.</Alert>}
        {mutation.isError && <Alert tone="error">{errorMessage(mutation.error)}</Alert>}
        <RouterLink to="/login" className="text-center text-blue-600 hover:underline">
          Go to sign in
        </RouterLink>
      </div>
    </AuthLayout>
  );
}
