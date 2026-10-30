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
import { register, resendVerification } from "../api/authApi";
import { AuthLayout } from "../components/AuthLayout";
import { registerSchema, type RegisterValues } from "../schemas/authSchemas";

export function RegisterPage() {
  const form = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { displayName: "", email: "", password: "", confirmPassword: "" },
  });
  const registerMutation = useMutation({
    mutationFn: register,
    onError: (error) => applyServerErrors(error, form.setError, ["displayName", "email", "password"]),
  });
  const resendMutation = useMutation({ mutationFn: resendVerification });

  if (registerMutation.isSuccess) {
    const email = registerMutation.data.email;
    return (
      <AuthLayout title="Check your inbox">
        <div className="flex flex-col gap-4 text-sm">
          <p>
            We sent a verification link to <strong>{email}</strong>. Open it to activate your account, then sign
            in.
          </p>
          {resendMutation.isSuccess ? (
            <Alert tone="success">Sent again.</Alert>
          ) : (
            <Button
              variant="secondary"
              isPending={resendMutation.isPending}
              onPress={() => resendMutation.mutate({ email })}
            >
              Resend email
            </Button>
          )}
          <RouterLink to="/login" className="text-center text-blue-600 hover:underline">
            Back to sign in
          </RouterLink>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Create your account"
      footer={
        <>
          Already registered?{" "}
          <RouterLink to="/login" className="text-blue-600 hover:underline">
            Sign in
          </RouterLink>
        </>
      }
    >
      <Form
        validationBehavior="aria"
        className="gap-4"
        onSubmit={form.handleSubmit(async (values) => {
          await registerMutation.mutateAsync(values).catch(() => undefined);
        })}
      >
        {registerMutation.isError && <Alert tone="error">{errorMessage(registerMutation.error)}</Alert>}
        <FormTextField control={form.control} name="displayName" label="Name" autoComplete="name" />
        <FormTextField control={form.control} name="email" label="Email" type="email" autoComplete="email" />
        <FormTextField
          control={form.control}
          name="password"
          label="Password"
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
        <Button type="submit" isPending={registerMutation.isPending}>
          Create account
        </Button>
      </Form>
    </AuthLayout>
  );
}
