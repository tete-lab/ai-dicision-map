import type { Metadata } from "next";
import { ScenePreview } from "@/components/workspace/ScenePreview";

export const metadata: Metadata = { title: "결정 지도 시연 | AI Decision Map", robots: { index: false, follow: false } };

export default function MapPreviewPage() {
  return <ScenePreview />;
}
