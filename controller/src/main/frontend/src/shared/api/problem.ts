import { z } from "zod";

export const problemSchema = z.object({
  title: z.string().optional(),
  status: z.number().optional(),
  detail: z.string().optional(),
  code: z.string().optional(),
  traceId: z.string().optional(),
  errors: z.array(z.object({ field: z.string(), message: z.string() })).optional(),
  /** Transfer chunk conflicts: where the upload has to resume. */
  receivedBytes: z.number().optional(),
  /** PERMISSION_REQUIRED from a phone: the missing phone access. */
  permission: z.string().optional(),
});

export type Problem = z.infer<typeof problemSchema>;

/** Error thrown for any non-2xx response; carries the server's problem-details fields. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: { field: string; message: string }[];
  readonly traceId?: string;
  readonly receivedBytes?: number;
  readonly permission?: string;

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed (${status})`);
    this.name = "ApiError";
    this.status = status;
    this.code = problem.code ?? (status >= 500 ? "INTERNAL" : "VALIDATION");
    this.fieldErrors = problem.errors ?? [];
    this.traceId = problem.traceId;
    this.receivedBytes = problem.receivedBytes;
    this.permission = problem.permission;
  }

  static async fromResponse(response: Response): Promise<ApiError> {
    let body: unknown = undefined;
    try {
      body = await response.json();
    } catch {
      // not JSON (proxy error page, empty body)
    }
    const parsed = problemSchema.safeParse(body);
    return new ApiError(response.status, parsed.success ? parsed.data : {});
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "Something went wrong";
}
