import type { Metadata } from "next";
import "@xyflow/react/dist/style.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "AI Decision Map | 생각을 선명하게 만드는 결정 지도",
  description:
    "AI와 대화하며 선택지와 판단 기준을 구조화하고, 나에게 중요한 우선순위를 발견해보세요.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
