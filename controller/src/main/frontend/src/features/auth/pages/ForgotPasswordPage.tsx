import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link as RouterLink } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { FormTextField } from "@/shared/components/form/FormTextField";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Form } from "@/shared/components/ui/shadcn/Form";
import { forgotPassword } from "../api/authApi";
import { AuthLayout } from "../components/AuthLayout";
import { emailSchema, type EmailValues } from "../schemas/authSchemas";

export function ForgotPasswordPage() {
  const form = useForm<EmailValues>({ resolver: zodResolver(emailSchema), defaultValues: { email: "" } });
  const mutation = useMutation({ mutationFn: forgotPassword });

  return (
    <AuthLayout
      title="Reset your password"
      footer={
        <RouterLink to="/login" className="text-blue-600 hover:underline">
          Back to sign in
        </RouterLink>
      }
    >
      {mutation.isSuccess ? (
        <Alert tone="success">
          If an account exists for {form.getValues("email")}, we sent a link to reset its password.
        </Alert>
      ) : (
        <Form
          validationBehavior="aria"
          className="gap-4"
          onSubmit={form.handleSubmit(async (values) => {
            await mutation.mutateAsync(values).catch(() => undefined);
          })}
        >
          {mutation.isError && <Alert tone="error">{errorMessage(mutation.error)}</Alert>}
          <FormTextField control={form.control} name="email" label="Email" type="email" autoComplete="email" />
          <Button type="submit" isPending={mutation.isPending}>
            Send reset link
          </Button>
        </Form>
      )}
    </AuthLayout>
  );
}
