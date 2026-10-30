import { z } from "zod";
import { api, tokenResponseSchema } from "@/shared/api/client";
import { sessionStore } from "@/shared/api/session";
import type { EmailValues, LoginValues, RegisterValues } from "../schemas/authSchemas";

const registerResponseSchema = z.object({ email: z.string() });

export async function login(values: LoginValues) {
  const tokens = await api.request("/api/auth/login", tokenResponseSchema, { json: values, auth: false });
  sessionStore.set({ accessToken: tokens.accessToken, user: tokens.user });
  return tokens.user;
}

export async function logout() {
  try {
    await api.request("/api/auth/logout", null, { method: "POST", auth: false });
  } finally {
    sessionStore.clear();
  }
}

export function register(values: RegisterValues) {
  const { displayName, email, password } = values;
  return api.request("/api/auth/register", registerResponseSchema, {
    json: { displayName, email, password },
    auth: false,
  });
}

export function verifyEmail(token: string) {
  return api.request("/api/auth/verify-email", null, { json: { token }, auth: false });
}

export function resendVerification(values: EmailValues) {
  return api.request("/api/auth/resend-verification", null, { json: values, auth: false });
}

export function forgotPassword(values: EmailValues) {
  return api.request("/api/auth/forgot-password", null, { json: values, auth: false });
}

export function resetPassword(token: string, password: string) {
  return api.request("/api/auth/reset-password", null, { json: { token, password }, auth: false });
}
