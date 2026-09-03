import { expect, it } from "vitest";
import { siteFooterText } from "./siteFooter";

it("keeps submission attribution until the final second of October 5 in Korea", () => {
  expect(siteFooterText(Date.parse("2026-10-05T23:59:59+09:00"))).toContain("김태건");
});
it("switches automatically at midnight October 6 Korean time", () => {
  expect(siteFooterText(Date.parse("2026-10-05T15:00:00Z"))).toBe("© 2026 tetelab. All rights reserved.");
});
it("updates the copyright year in Korean time", () => {
  expect(siteFooterText(Date.parse("2026-12-31T15:00:00Z"))).toContain("© 2027");
});
