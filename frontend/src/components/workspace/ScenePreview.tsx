"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { DecisionMapGraph } from "./DecisionMapGraph";
import { sceneExample } from "@/lib/sceneExamples";
import { sceneThemes, type SceneTheme } from "@/lib/decisionScene";

export function ScenePreview() {
  const [theme, setTheme] = useState<SceneTheme>("career");
  const result = useMemo(() => sceneExample(theme), [theme]);
  return <main className="world-preview"><header><Link href="/">← AI Decision Map</Link><h1>고민마다 다른 결정의 풍경</h1><p>실제 Three.js 렌더링 시연입니다. 아래 대화·평가값은 가상 예시이며 DB 저장이나 AI 호출은 하지 않습니다.</p><label>가상 고민 선택<select value={theme} onChange={(e) => setTheme(e.target.value as SceneTheme)}>{Object.entries(sceneThemes).map(([id, t]) => <option value={id} key={id}>{t.label}</option>)}</select></label></header><DecisionMapGraph result={result} /></main>;
}
