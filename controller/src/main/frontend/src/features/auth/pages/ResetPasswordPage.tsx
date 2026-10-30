import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link as RouterLink } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { applyServerErrors } from "@/shared/components/form/applyServerErrors";
import { FormTextField } from "@/shared/components/form/FormTextField";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Form } from "@/shared/components/ui/shadcn/Form";
import { resetPassword } from "../api/authApi";
import { AuthLayout } from "../components/AuthLayout";
import { resetPasswordSchema, type ResetPasswordValues } from "../schemas/authSchemas";

export function ResetPasswordPage({ token }: { token: string }) {
  const form = useForm<ResetPasswordValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { password: "", confirmPassword: "" },
  });
  const mutation = useMutation({
    mutationFn: (values: ResetPasswordValues) => resetPassword(token, values.password),
    onError: (error) => applyServerErrors(error, form.setError, ["password"]),
  });

  return (
    <AuthLayout title="Choose a new password">
      {mutation.isSuccess ? (
        <div className="flex flex-col gap-4 text-sm">
          <Alert tone="success">Your password was changed. Other sessions were signed out.</Alert>
          <RouterLink to="/login" className="text-center text-blue-600 hover:underline">
            Sign in
          </RouterLink>
        </div>
      ) : (
        <Form
          validationBehavior="aria"
          className="gap-4"
          onSubmit={form.handleSubmit(async (values) => {
            await mutation.mutateAsync(values).catch(() => undefined);
          })}
        >
          {!token && <Alert tone="error">This link is incomplete. Request a new one.</Alert>}
          {mutation.isError && <Alert tone="error">{errorMessage(mutation.error)}</Alert>}
          <FormTextField
            control={form.control}
            name="password"
            label="New password"
            type="password"
            autoComplete="new-password"
            description="At least 10 characters"
          />
          <FormTextField
            control={form.control}
            name="confirmPassword"
            label="Confirm password"
            type="password"
            autoComplete="new-password"
          />
          <Button type="submit" isDisabled={!token} isPending={mutation.isPending}>
            Change password
          </Button>
          <RouterLink to="/forgot-password" className="text-center text-sm text-blue-600 hover:underline">
            Request a new link
          </RouterLink>
        </Form>
      )}
    </AuthLayout>
  );
}
