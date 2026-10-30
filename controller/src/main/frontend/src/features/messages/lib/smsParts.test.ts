import { describe, expect, it } from "vitest";
import { smsParts } from "./smsParts";

describe("smsParts", () => {
  it("counts GSM-7 messages in 160 / 153 septet parts", () => {
    expect(smsParts("")).toEqual({ encoding: "GSM_7", parts: 0, remaining: 160 });
    expect(smsParts("Hello")).toEqual({ encoding: "GSM_7", parts: 1, remaining: 155 });
    expect(smsParts("a".repeat(160))).toEqual({ encoding: "GSM_7", parts: 1, remaining: 0 });
    expect(smsParts("a".repeat(161))).toEqual({ encoding: "GSM_7", parts: 2, remaining: 145 });
    expect(smsParts("a".repeat(306))).toEqual({ encoding: "GSM_7", parts: 2, remaining: 0 });
    expect(smsParts("a".repeat(307)).parts).toBe(3);
  });

  it("charges two septets for extension characters", () => {
    expect(smsParts("€")).toEqual({ encoding: "GSM_7", parts: 1, remaining: 158 });
    expect(smsParts("{".repeat(80))).toEqual({ encoding: "GSM_7", parts: 1, remaining: 0 });
    expect(smsParts("{".repeat(81)).parts).toBe(2);
  });

  it("switches the whole message to UCS-2 for any other character", () => {
    expect(smsParts("Grüße")).toMatchObject({ encoding: "GSM_7" });
    expect(smsParts("Cześć")).toEqual({ encoding: "UCS_2", parts: 1, remaining: 65 });
    expect(smsParts(`${"a".repeat(69)}ł`)).toEqual({ encoding: "UCS_2", parts: 1, remaining: 0 });
    expect(smsParts(`${"a".repeat(70)}ł`)).toEqual({ encoding: "UCS_2", parts: 2, remaining: 63 });
  });

  it("counts emoji as two UTF-16 units", () => {
    expect(smsParts("👍")).toEqual({ encoding: "UCS_2", parts: 1, remaining: 68 });
  });
});
