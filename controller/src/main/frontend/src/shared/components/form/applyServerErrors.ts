import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiError } from "@/shared/api/problem";

/**
 * Copies field errors from a VALIDATION problem onto the form. Returns true when at least one field
 * matched, so callers can fall back to a form-level message otherwise.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  if (!(error instanceof ApiError)) return false;
  let matched = false;
  for (const fieldError of error.fieldErrors) {
    const field = fields.find((name) => name === fieldError.field);
    if (field) {
      setError(field, { type: "server", message: fieldError.message });
      matched = true;
    }
  }
  return matched;
}
