"use client";

import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Building2, Compass, Home, Scale, ShoppingBag, RotateCcw, Minus, Plus } from "lucide-react";
import { balanceContributions, detectSceneTheme, sceneThemes, selectComparisonPair, type SceneResult, type SceneTheme } from "@/lib/decisionScene";
import type { DecisionSceneController } from "./scene/createDecisionScene";

const icons = { career: Building2, travel: Compass, balance: Scale, purchase: ShoppingBag, housing: Home };
const sources = { USER_FACT: "사용자 진술", USER_ASSUMPTION: "사용자 가정", AI_INFERENCE: "AI 추론" };
const reducedQuery = "(prefers-reduced-motion: reduce)";
function subscribeMotion(callback: () => void) {
  const query = window.matchMedia(reducedQuery);
  query.addEventListener("change", callback);
  return () => query.removeEventListener("change", callback);
}

export function DecisionMapGraph({ result }: { result: SceneResult }) {
  return <DecisionWorld key={result.sessionId} result={result} />;
}

function DecisionWorld({ result }: { result: SceneResult }) {
  const suggested = detectSceneTheme(result);
  const [choice, setChoice] = useState<SceneTheme | "auto">("auto");
  const theme = choice === "auto" ? suggested : choice;
  const [pairIds, setPairIds] = useState<string[]>([]);
  const pair = useMemo(() => selectComparisonPair(result, pairIds), [result, pairIds]);
  const [selection, setSelection] = useState({ optionId: result.options[0]?.id ?? "", criterionId: result.criteria[0]?.id ?? "" });
  const [simple, setSimple] = useState(false);
  const [explore, setExplore] = useState(false);
  const [motionOff, setMotionOff] = useState(false);
  const reduced = useSyncExternalStore(subscribeMotion, () => window.matchMedia(reducedQuery).matches, () => true);
  const motion = !reduced && !motionOff;
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const host = useRef<HTMLDivElement>(null);
  const labels = useRef<HTMLDivElement>(null);
  const controller = useRef<DecisionSceneController | null>(null);
  const balance = useMemo(() => balanceContributions(result, pair[0]?.id ?? "", pair[1]?.id ?? ""), [result, pair]);
  const selectedOption = pair.find((o) => o.id === selection.optionId) ?? pair[0];
  const selectedCriterion = result.criteria.find((c) => c.id === selection.criterionId) ?? result.criteria[0];
  const profile = result.optionProfiles.find((p) => p.optionId === selectedOption?.id);
  const assessment = result.assessments.find((a) => a.optionId === selectedOption?.id && a.criterionId === selectedCriterion?.id);

  useEffect(() => {
    if (simple || !host.current || !labels.current || pair.length < 2) return;
    let disposed = false;
    let instance: DecisionSceneController | null = null;
    const mount = host.current, overlay = labels.current;
    // This chunk is requested only when the result map is actually mounted.
    import("./scene/createDecisionScene").then(({ createDecisionScene }) => {
      if (disposed) return;
      try {
        instance = createDecisionScene({ host: mount, labels: overlay, result, theme, pair, onSelect: setSelection, onFailure: () => setStatus("failed") });
        controller.current = instance;
        setStatus("ready");
      } catch {
        setStatus("failed");
      }
    }).catch(() => { if (!disposed) setStatus("failed"); });
    return () => { disposed = true; instance?.dispose(); controller.current = null; };
  }, [result, theme, pair, simple]);

  useEffect(() => { controller.current?.setMotion(motion); }, [motion, status, theme, pair, simple]);
  useEffect(() => { controller.current?.setExplore(explore); }, [explore, status, theme, pair, simple]);
  useEffect(() => {
    controller.current?.select(selectedOption?.id ?? "", selectedCriterion?.id ?? "");
  }, [selectedOption?.id, selectedCriterion?.id, status, theme, pair, simple]);

  function changeTheme(value: SceneTheme | "auto") {
    const nextTheme = value === "auto" ? suggested : value;
    setChoice(value);
    if (nextTheme !== theme) setStatus("loading");
    setExplore(false);
  }
  const summaryMode = simple || status === "failed" || pair.length < 2;
  return <section className="decision-world" aria-label="고민 유형별 결정 지도">
    <header className="world-heading">
      <div><span className="world-eyebrow">DECISION WORLD · {choice === "auto" ? "자동 추천" : "직접 선택"}</span><h3>{sceneThemes[theme].title}</h3><p>{sceneThemes[theme].description}</p></div>
      <label className="world-theme-label">화면 유형<select value={choice} onChange={(e) => changeTheme(e.target.value as SceneTheme | "auto")}><option value="auto">자동 · {sceneThemes[suggested].label}</option>{Object.entries(sceneThemes).map(([id, config]) => <option key={id} value={id}>{config.label}</option>)}</select></label>
    </header>
    <div className="world-themes" aria-label="시각화 유형">{(Object.keys(sceneThemes) as SceneTheme[]).map((id) => { const Icon = icons[id]; return <button key={id} type="button" aria-pressed={theme === id} onClick={() => changeTheme(id)}><Icon size={17} aria-hidden="true" />{sceneThemes[id].label}</button>; })}</div>
    {result.options.length > 2 && <div className="world-pair"><span>전체 {result.options.length}개 중 두 대안을 나란히 비교</span>{pair.map((option, index) => <label key={index}>{index === 0 ? "왼쪽 대안" : "오른쪽 대안"}<select value={option.id} onChange={(e) => { const next = pair.map((o) => o.id); next[index] = e.target.value; setPairIds(next); setStatus("loading"); }}>{result.options.map((o) => <option key={o.id} value={o.id} disabled={o.id === pair[1 - index]?.id}>{o.name}</option>)}</select></label>)}</div>}
    <div className="world-controls">
      <label><input type="checkbox" checked={simple} onChange={(e) => { setSimple(e.target.checked); setStatus("loading"); }} />간단히 보기 (2D)</label>
      {!summaryMode && <><label><input type="checkbox" checked={motionOff || reduced} disabled={reduced} onChange={(e) => setMotionOff(e.target.checked)} />{reduced ? "기기 설정: 동작 줄임" : "동작 줄이기"}</label><button type="button" className="world-explore" aria-pressed={explore} onClick={() => setExplore(!explore)}>{explore ? "지도 탐색 종료" : "지도 탐색"}</button></>}
    </div>
    <div className="world-stage-wrap">
      <div ref={host} className={`world-stage theme-${theme} ${explore ? "is-exploring" : ""}`} hidden={summaryMode} role="group" aria-label={`${sceneThemes[theme].title} 3D 장면. 아래 대안과 기준 버튼으로도 탐색할 수 있습니다.`}>
        <div ref={labels} className="world-labels" />
        {status === "loading" && <div className="world-loading" role="status">결정 지도를 준비하고 있어요…</div>}
      </div>
      {summaryMode && <div className="world-flat"><p role="status">{status === "failed" ? "이 환경에서는 3D를 표시할 수 없어 간단한 비교로 전환했어요." : "효과 없이 같은 대안과 근거를 비교합니다."}</p><div>{pair.map((o, i) => <button key={o.id} type="button" aria-pressed={o.id === selectedOption?.id} onClick={() => setSelection({ ...selection, optionId: o.id })}><span>{i === 0 ? "A" : "B"}</span><strong>{o.name}</strong><small>{result.optionProfiles.find((p) => p.optionId === o.id)?.bestWhen || "적합한 조건을 아래에서 확인하세요."}</small></button>)}</div></div>}
      {!summaryMode && status === "ready" && <div className="world-camera"><button type="button" aria-label="지도 축소" onClick={() => controller.current?.zoom(0.85)}><Minus size={18} /></button><button type="button" aria-label="지도 확대" onClick={() => controller.current?.zoom(1.18)}><Plus size={18} /></button><button type="button" aria-label="지도 시점 초기화" onClick={() => controller.current?.reset()}><RotateCcw size={17} /></button></div>}
      {!summaryMode && theme === "balance" && <div className="world-playback"><button type="button" disabled={!motion || !balance.hasData || status !== "ready"} onClick={() => controller.current?.replay()}>무게추 다시 보기</button><button type="button" onClick={() => controller.current?.finish()}>최종 균형 보기</button></div>}
    </div>
    <p className="world-disclaimer">{theme === "balance" ? (!balance.hasData ? "비교 가능한 공통 평가가 없어 저울을 기울이지 않았어요." : `공통 기준 ${balance.contributions.length}개를 반영했습니다. 기울기는 상대 평가의 가중 합이며 추천 확정이 아닙니다.`) : "건물·지형·모형의 크기는 점수나 성공 확률을 나타내지 않습니다."}{theme === "balance" && balance.missing.length > 0 && <span> 미반영 기준: {balance.missing.join(", ")} — 양쪽 평가와 중요도를 확인하세요.</span>}</p>
    <div className="world-selection">
      <div className="world-option-tabs" aria-label="근거를 볼 대안">{pair.map((o, i) => <button key={o.id} type="button" aria-pressed={o.id === selectedOption?.id} onClick={() => setSelection({ ...selection, optionId: o.id })}><span>{i === 0 ? "A" : "B"}</span>{o.name}</button>)}</div>
      <div className="world-criteria" aria-label="판단 기준">{result.criteria.map((c) => <button key={c.id} type="button" aria-pressed={c.id === selectedCriterion?.id} onClick={() => setSelection({ optionId: selectedOption?.id ?? "", criterionId: c.id })}>{c.name}</button>)}</div>
      <article className="world-evidence" aria-live="polite">
        <header><span>선택한 판단 기준</span><h4>{selectedOption?.name ?? "선택지 미확인"} · {selectedCriterion?.name ?? "기준 미확인"}</h4></header>
        <p>{assessment?.reason || "이 기준의 구체적인 평가 이유가 아직 기록되지 않았어요. 실제 조건을 확인해 주세요."}</p>
        <small>{assessment ? sources[assessment.sourceType] : "미확인"} · 외부 검증된 사실과는 다릅니다.</small>
        {profile && <div className="world-pros-cons"><div><b>기대할 장점</b>{profile.pros.map((p, i) => <p key={i}>{p}</p>)}</div><div><b>확인할 부담</b>{profile.cons.map((p, i) => <p key={i}>{p}</p>)}</div></div>}
        {!!profile?.evidenceRefs?.length && <details><summary>이 대안의 대화 근거</summary>{profile.evidenceRefs.map((e, i) => <p key={i}>{e}</p>)}</details>}
      </article>
    </div>
  </section>;
}
