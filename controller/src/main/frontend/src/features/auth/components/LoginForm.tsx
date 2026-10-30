import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { FormTextField } from "@/shared/components/form/FormTextField";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Form } from "@/shared/components/ui/shadcn/Form";
import { loginSchema, type LoginValues } from "../schemas/authSchemas";

type LoginFormProps = {
  onSubmit: (values: LoginValues) => Promise<void>;
  isPending?: boolean;
};

export function LoginForm({ onSubmit, isPending }: LoginFormProps) {
  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  return (
    <Form validationBehavior="aria" onSubmit={form.handleSubmit(onSubmit)} className="gap-4">
      <FormTextField control={form.control} name="email" label="Email" type="email" autoComplete="email" />
      <FormTextField
        control={form.control}
        name="password"
        label="Password"
        type="password"
        autoComplete="current-password"
      />
      <Button type="submit" isPending={isPending}>
        Sign in
      </Button>
    </Form>
  );
}
