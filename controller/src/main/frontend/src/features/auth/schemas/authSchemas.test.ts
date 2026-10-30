import { describe, expect, it } from "vitest";
import { loginSearchSchema, registerSchema, resetPasswordSchema } from "./authSchemas";

describe("registerSchema", () => {
  const valid = {
    displayName: "Ada",
    email: "ada@example.com",
    password: "correct-horse",
    confirmPassword: "correct-horse",
  };

  it("accepts a valid registration", () => {
    expect(registerSchema.safeParse(valid).success).toBe(true);
  });

  it("requires matching passwords", () => {
    const result = registerSchema.safeParse({ ...valid, confirmPassword: "something-else" });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual(["confirmPassword"]);
  });

  it("rejects short passwords and bad emails", () => {
    const result = registerSchema.safeParse({ ...valid, email: "nope", password: "short", confirmPassword: "short" });
    const paths = result.error?.issues.map((issue) => issue.path[0]);
    expect(paths).toContain("email");
    expect(paths).toContain("password");
  });

  it("trims the display name", () => {
    expect(registerSchema.safeParse({ ...valid, displayName: "   " }).success).toBe(false);
  });
});

describe("resetPasswordSchema", () => {
  it("enforces the same rules as registration", () => {
    expect(resetPasswordSchema.safeParse({ password: "long-enough-1", confirmPassword: "long-enough-1" }).success).toBe(
      true,
    );
    expect(resetPasswordSchema.safeParse({ password: "short", confirmPassword: "short" }).success).toBe(false);
  });
});

describe("loginSearchSchema", () => {
  it("keeps same-origin redirect paths", () => {
    expect(loginSearchSchema.parse({ redirect: "/devices/abc" }).redirect).toBe("/devices/abc");
  });

  it("drops absolute and protocol-relative redirects", () => {
    expect(loginSearchSchema.parse({ redirect: "https://evil.example" }).redirect).toBeUndefined();
    expect(loginSearchSchema.parse({ redirect: "//evil.example" }).redirect).toBeUndefined();
    expect(loginSearchSchema.parse({}).redirect).toBeUndefined();
  });
});
