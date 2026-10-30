import { useMutation } from "@tanstack/react-query";
import { Link as RouterLink, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { ApiError, errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { login, resendVerification } from "../api/authApi";
import { AuthLayout } from "../components/AuthLayout";
import { LoginForm } from "../components/LoginForm";
import type { LoginValues } from "../schemas/authSchemas";

export function LoginPage({ redirectTo }: { redirectTo?: string }) {
  const navigate = useNavigate();
  const [unverifiedEmail, setUnverifiedEmail] = useState<string | null>(null);
  const loginMutation = useMutation({
    mutationFn: login,
    onSuccess: () => navigate({ to: redirectTo ?? "/devices" }),
    onError: (error, values) => {
      setUnverifiedEmail(error instanceof ApiError && error.code === "EMAIL_NOT_VERIFIED" ? values.email : null);
    },
  });
  const resendMutation = useMutation({ mutationFn: resendVerification });

  return (
    <AuthLayout
      title="Sign in"
      footer={
        <>
          New here?{" "}
          <RouterLink to="/register" className="text-blue-600 hover:underline">
            Create an account
          </RouterLink>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {loginMutation.isError && <Alert tone="error">{errorMessage(loginMutation.error)}</Alert>}
        {unverifiedEmail && (
          <div className="flex flex-col gap-2">
            {resendMutation.isSuccess ? (
              <Alert tone="success">We sent a new verification link to {unverifiedEmail}.</Alert>
            ) : (
              <Button
                variant="secondary"
                isPending={resendMutation.isPending}
                onPress={() => resendMutation.mutate({ email: unverifiedEmail })}
              >
                Resend verification email
              </Button>
            )}
          </div>
        )}
        <LoginForm
          isPending={loginMutation.isPending}
          onSubmit={async (values: LoginValues) => {
            await loginMutation.mutateAsync(values).catch(() => undefined);
          }}
        />
        <RouterLink to="/forgot-password" className="text-center text-sm text-blue-600 hover:underline">
          Forgot your password?
        </RouterLink>
      </div>
    </AuthLayout>
  );
}
