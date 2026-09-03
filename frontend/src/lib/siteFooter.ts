// October 5 is inclusive, regardless of the viewer's local timezone.
export const SUBMISSION_END = Date.parse("2026-10-06T00:00:00+09:00");

export function siteFooterText(now: number): string {
  if (now < SUBMISSION_END) return "이 사이트는 김태건의 원티드 AI Championship 2026 제출용 입니다.";
  const year = new Intl.DateTimeFormat("en", { timeZone: "Asia/Seoul", year: "numeric" }).format(now);
  return `© ${year} tetelab. All rights reserved.`;
}
