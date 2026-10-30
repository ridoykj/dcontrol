import { z } from "zod";
import { ApiError } from "./problem";
import { sessionStore, type SessionStore } from "./session";

export const tokenResponseSchema = z.object({
  accessToken: z.string(),
  tokenType: z.literal("Bearer"),
  expiresIn: z.number(),
  user: z.object({
    id: z.string(),
    accountId: z.string(),
    email: z.string(),
    displayName: z.string(),
  }),
});

export type TokenResponse = z.infer<typeof tokenResponseSchema>;

type RequestOptions = {
  method?: string;
  json?: unknown;
  /** Raw body (file chunks); mutually exclusive with json. */
  body?: Blob;
  headers?: Record<string, string>;
  /** Send the access token and retry once after a refresh on 401. Default true. */
  auth?: boolean;
  signal?: AbortSignal;
};

const CSRF_HEADER = { "X-Requested-With": "XMLHttpRequest" };

/**
 * fetch wrapper: JSON in and out, problem-details errors, and transparent access-token refresh.
 * Concurrent 401s share a single refresh call.
 */
export function createApiClient(store: SessionStore, fetchImpl: typeof fetch = (...args) => fetch(...args)) {
  let refreshing: Promise<boolean> | null = null;

  async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
    const headers: Record<string, string> = { Accept: "application/json", ...CSRF_HEADER };
    if (options.json !== undefined) headers["Content-Type"] = "application/json";
    if (token && options.auth !== false) headers.Authorization = `Bearer ${token}`;
    Object.assign(headers, options.headers);
    const hasBody = options.json !== undefined || options.body !== undefined;
    return fetchImpl(path, {
      method: options.method ?? (hasBody ? "POST" : "GET"),
      headers,
      body: options.body ?? (options.json === undefined ? undefined : JSON.stringify(options.json)),
      credentials: "same-origin",
      signal: options.signal,
    });
  }

  async function doRefresh(): Promise<boolean> {
    for (let attempt = 0; attempt < 2; attempt++) {
      const response = await fetchImpl("/api/auth/refresh", {
        method: "POST",
        headers: { Accept: "application/json", ...CSRF_HEADER },
        credentials: "same-origin",
      });
      if (response.ok) {
        const tokens = tokenResponseSchema.parse(await response.json());
        store.set({ accessToken: tokens.accessToken, user: tokens.user });
        return true;
      }
      // Another tab may have rotated the cookie a moment ago; the browser now holds the new one.
      if (response.status === 401 && attempt === 0) {
        await new Promise((resolve) => setTimeout(resolve, 300));
        continue;
      }
      break;
    }
    store.clear();
    return false;
  }

  /** Single-flight refresh; resolves to whether a session is now available. */
  function refresh(): Promise<boolean> {
    if (!refreshing) {
      refreshing = doRefresh()
        .catch(() => {
          store.clear();
          return false;
        })
        .finally(() => {
          refreshing = null;
        });
    }
    return refreshing;
  }

  async function fetchOk(path: string, options: RequestOptions): Promise<Response> {
    let response = await send(path, options, store.get().accessToken);
    if (response.status === 401 && options.auth !== false) {
      if (await refresh()) {
        response = await send(path, options, store.get().accessToken);
      }
    }
    if (!response.ok) {
      throw await ApiError.fromResponse(response);
    }
    return response;
  }

  async function request<T>(path: string, schema: z.ZodType<T> | null, options: RequestOptions = {}): Promise<T> {
    const response = await fetchOk(path, options);
    if (schema === null || response.status === 204) {
      return undefined as T;
    }
    return schema.parse(await response.json());
  }

  /** Authenticated binary GET (app icons). */
  async function requestBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
    return (await fetchOk(path, options)).blob();
  }

  return { request, requestBlob, refresh };
}

export const api = createApiClient(sessionStore);
