import { describe, expect, it } from "vitest";
import { buildApiUrl } from "./api";
import { scenarios } from "./scenarios";

describe("landing page configuration", () => {
  it("contains the six required decision scenarios", () => {
    expect(scenarios.map((scenario) => scenario.category)).toEqual([
      "주거",
      "여행",
      "커리어",
      "구매",
      "학습",
      "비즈니스",
    ]);
  });

  it("builds an API URL without duplicate slashes", () => {
    expect(buildApiUrl("/api/v1/health", "http://localhost:8013/")).toBe(
      "http://localhost:8013/api/v1/health",
    );
  });

  it("supports same-origin API routing", () => {
    expect(buildApiUrl("api/v1/health", "")).toBe("/api/v1/health");
  });

  it("encodes a session id when building a decision state route", () => {
    expect(
      buildApiUrl(`/api/v1/decisions/${encodeURIComponent("session/id")}/state`, ""),
    ).toBe("/api/v1/decisions/session%2Fid/state");
  });
});
