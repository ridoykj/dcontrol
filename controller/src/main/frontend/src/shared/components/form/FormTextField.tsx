import { Controller, type Control, type FieldPath, type FieldValues } from "react-hook-form";
import { TextField, type TextFieldProps } from "@/shared/components/ui/shadcn/TextField";

type FormTextFieldProps<T extends FieldValues> = Omit<
  TextFieldProps,
  "name" | "value" | "defaultValue" | "onChange" | "onBlur" | "isInvalid" | "errorMessage"
> & {
  control: Control<T>;
  name: FieldPath<T>;
};

/** React Aria text field bound to react-hook-form; zod errors show under the field. */
export function FormTextField<T extends FieldValues>({ control, name, ...props }: FormTextFieldProps<T>) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <TextField
          {...props}
          name={field.name}
          value={(field.value as string | undefined) ?? ""}
          onChange={field.onChange}
          onBlur={field.onBlur}
          isInvalid={fieldState.invalid}
          errorMessage={fieldState.error?.message}
          validationBehavior="aria"
        />
      )}
    />
  );
}
