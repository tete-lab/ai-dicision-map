import type { Metadata } from "next";
import "@xyflow/react/dist/style.css";
import "./globals.css";

const siteUrl = "https://ai-decision.tetelab.dev";
const siteDescription = "AI와 대화하며 선택지와 판단 기준을 구조화하고, 결정 지도와 실행 계획으로 다음 선택을 선명하게 만드는 서비스입니다.";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: "AI Decision Map | 생각을 선명하게 만드는 결정 지도",
  description: siteDescription,
  openGraph: {
    type: "website",
    locale: "ko_KR",
    url: siteUrl,
    siteName: "AI Decision Map",
    title: "AI Decision Map | 고민을 결정 지도로 바꿔보세요",
    description: siteDescription,
  },
  twitter: {
    card: "summary_large_image",
    title: "AI Decision Map | 고민을 결정 지도로 바꿔보세요",
    description: siteDescription,
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
