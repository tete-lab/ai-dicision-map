"use client";

import Image from "next/image";
import { useMemo, useState } from "react";
import { Building2, CheckCircle2, Compass, Home, RotateCcw, Scale, ShoppingBag, Sparkles } from "lucide-react";
import { balanceContributions, detectSceneTheme, sceneMetric, sceneThemes, selectComparisonPair, type SceneResult, type SceneTheme } from "@/lib/decisionScene";

const icons = { career: Building2, travel: Compass, balance: Scale, purchase: ShoppingBag, housing: Home };
const sources = { USER_FACT: "사용자 진술", USER_ASSUMPTION: "사용자 가정", AI_INFERENCE: "AI 추론" };
const art: Partial<Record<SceneTheme, { src: string; alt: string }>> = {
  career: { src: "/decision-atlas/career.webp", alt: "서로 다른 업무 환경을 나타낸 두 개의 도시 모형" },
  travel: { src: "/decision-atlas/travel.webp", alt: "서로 다른 여행 경험을 나타낸 두 개의 섬 모형" },
  housing: { src: "/decision-atlas/housing.webp", alt: "서로 다른 생활 조건을 나타낸 두 개의 주거 지역 모형" },
  purchase: { src: "/decision-atlas/purchase.webp", alt: "현재 것을 유지하는 경우와 새 제품을 선택하는 경우의 생활 모형" },
};

export function DecisionMapGraph({ result }: { result: SceneResult }) {
  return <DecisionAtlas key={result.sessionId} result={result} />;
}

function DecisionAtlas({ result }: { result: SceneResult }) {
  const theme = detectSceneTheme(result);
  const ThemeIcon = icons[theme];
  const [pairIds, setPairIds] = useState<string[]>([]);
  const pair = useMemo(() => selectComparisonPair(result, pairIds), [result, pairIds]);
  const [criterionId, setCriterionId] = useState(result.criteria[0]?.id ?? "");
  const [replay, setReplay] = useState(0);
  const selected = result.criteria.find((criterion) => criterion.id === criterionId) ?? result.criteria[0];
  const balance = useMemo(() => balanceContributions(result, pair[0]?.id ?? "", pair[1]?.id ?? ""), [result, pair]);
  const profiles = pair.map((option) => result.optionProfiles.find((profile) => profile.optionId === option?.id));
  const assessments = pair.map((option) => result.assessments.find((item) => item.optionId === option?.id && item.criterionId === selected?.id));
  const preference = useMemo(() => resolvePreference(result, pair), [result, pair]);
  const preferredProfile = preference.option ? result.optionProfiles.find((profile) => profile.optionId === preference.option?.id) : undefined;

  if (pair.length < 2) return <section className="decision-atlas atlas-empty"><Compass size={28} /><h3>비교할 선택지가 더 필요해요</h3><p>두 개 이상의 선택지가 확인되면 결정 지도를 그려드릴게요.</p></section>;

  return <section className={`decision-atlas atlas-${theme}`} aria-label="질문 유형에 맞춘 결정 지도">
    <header className="atlas-heading"><div><span className="atlas-eyebrow">DECISION ATLAS · 선택이 만드는 변화</span><h3>{sceneThemes[theme].title}</h3><p>{atlasDescription(theme)}</p></div><span className="atlas-theme"><ThemeIcon size={18} aria-hidden="true" />{sceneThemes[theme].label}</span></header>
    {result.options.length > 2 && <div className="atlas-pair"><span>전체 {result.options.length}개 중 두 대안을 비교하고 있어요.</span>{pair.map((option, index) => <label key={index}>{index === 0 ? "왼쪽 대안" : "오른쪽 대안"}<select value={option.id} onChange={(event) => { const next = pair.map((item) => item.id); next[index] = event.target.value; setPairIds(next); }}>{result.options.map((item) => <option key={item.id} value={item.id} disabled={item.id === pair[1 - index]?.id}>{item.name}</option>)}</select></label>)}</div>}
    <section className={`atlas-verdict ${preference.option ? "decisive" : "needs-check"}`} aria-label="결정 지도 추천 결론"><span><CheckCircle2 size={17} />{preference.option ? "추천 결론" : "결정 전 확인"}</span><div><h4>{preference.option ? <>지금은 <strong>‘{preference.option.name}’</strong> 쪽을 지지해요</> : "두 선택의 차이가 아직 크지 않아요"}</h4><p>{result.guidance.headline}{result.guidance.rationale && <> · {result.guidance.rationale}</>}</p></div><aside>{preference.factors.length > 0 && <div><small>이 선택에 힘을 보탠 기준</small><p>{preference.factors.map((factor) => <b key={factor}>{factor}</b>)}</p></div>}{result.guidance.nextAction && <div><small>먼저 해볼 일</small><strong><Sparkles size={14} />{result.guidance.nextAction}</strong></div>}{!result.guidance.nextAction && preferredProfile?.pros?.[0] && <div><small>기대할 변화</small><strong><Sparkles size={14} />{preferredProfile.pros[0]}</strong></div>}</aside></section>
    <div className="atlas-layout"><div className="atlas-map-column">
      <div className="atlas-map-head"><strong>{theme === "balance" ? "이유가 쌓이면 방향이 보여요" : "추천 경로와 고민 포인트를 함께 보세요"}</strong><span>기준을 누르면 양쪽 판단 근거가 바뀝니다</span></div>
      {theme === "balance" ? <BalanceMap pair={pair} balance={balance} preferredId={preference.option?.id ?? null} selectedId={selected?.id ?? ""} replay={replay} onSelect={setCriterionId} onReplay={() => setReplay((value) => value + 1)} /> : <LandscapeMap theme={theme} pair={pair} result={result} preferredId={preference.option?.id ?? null} selectedId={selected?.id ?? ""} onSelect={setCriterionId} />}
      <div className="atlas-legend"><span><i className="fact" />입력한 평가</span><span><i className="meaning" />AI가 정리한 의미</span><span><i className="unknown" />확인할 조건</span></div>
    </div><aside className="atlas-evidence" aria-live="polite"><span className="atlas-evidence-tag">근거 들여다보기</span><h4>{selected?.name ?? "확인할 조건"}</h4>
      {pair.map((option, index) => <section key={option.id} data-side={index}><header><span>{index === 0 ? "A" : "B"}</span><strong>{option.name}</strong><b>{selected ? sceneMetric(result, option.id, selected.id).scoreLabel : "미확인"}</b></header><p>{assessments[index]?.reason || "이 기준을 판단할 구체적인 근거가 아직 없어요."}</p><small>{assessments[index] ? sources[assessments[index]!.sourceType] : "미확인"} · 외부 검증된 사실과는 다릅니다.</small></section>)}
      <div className="atlas-tradeoff"><div><b>기대할 변화</b><p>{profiles[1]?.pros?.[0] || profiles[0]?.pros?.[0] || "선택 이후 기대하는 변화를 확인해보세요."}</p></div><div><b>확인할 부담</b><p>{profiles[1]?.cons?.[0] || profiles[0]?.cons?.[0] || "결정 전에 실제 조건을 확인해보세요."}</p></div></div>
    </aside></div>
    {result.criteria.length > 4 && <div className="atlas-metrics" aria-label="추가 고민 포인트 비교">{result.criteria.slice(4).map((criterion, index) => <article key={criterion.id} className={criterion.id === selected?.id ? "selected" : ""}><button type="button" onClick={() => setCriterionId(criterion.id)} aria-pressed={criterion.id === selected?.id}><span>{String(index + 5).padStart(2, "0")}</span><strong>{criterion.name}</strong><small>{sceneMetric(result, pair[0].id, criterion.id).weightLabel}</small></button><div>{pair.map((option, side) => { const metric = sceneMetric(result, option.id, criterion.id); return <span key={option.id} data-side={side}><i style={{ width: `${metric.score ?? 0}%` }} /><b>{option.name}</b><em>{metric.scoreLabel}</em></span>; })}</div></article>)}</div>}
    <p className="atlas-disclaimer">풍경은 선택의 맥락을, 경로와 카드의 수치는 사용자가 입력한 상대 평가를 보여줍니다. 점수는 성공 확률이나 검증된 사실이 아닙니다.</p>
  </section>;
}

function resolvePreference(result: SceneResult, pair: SceneResult["options"]) {
  const explicit = pair.find((option) => option.id === result.guidance.encouragedOptionId);
  const scored = pair.map((option) => ({ option, score: aggregatePreferenceScore(result, option.id) })).sort((a, b) => b.score - a.score);
  const option = explicit ?? (scored.length > 1 && scored[0].score - scored[1].score >= 1 ? scored[0].option : null);
  if (!option) return { option: null, factors: [] as string[] };
  const other = pair.find((candidate) => candidate.id !== option.id);
  const factors = other ? result.criteria.map((criterion) => {
    const preferred = sceneMetric(result, option.id, criterion.id).score;
    const alternative = sceneMetric(result, other.id, criterion.id).score;
    return { name: criterion.name, effect: preferred === null || alternative === null ? -Infinity : (preferred - alternative) * Math.max(criterion.normalizedWeight, 0) };
  }).filter((item) => item.effect > 0).sort((a, b) => b.effect - a.effect).slice(0, 3).map((item) => item.name) : [];
  return { option, factors };
}

function aggregatePreferenceScore(result: SceneResult, optionId: string) {
  const option = result.options.find((candidate) => candidate.id === optionId);
  if (option?.score !== null && option?.score !== undefined && Number.isFinite(option.score)) return option.score;
  let weighted = 0, covered = 0;
  for (const criterion of result.criteria) {
    const score = sceneMetric(result, optionId, criterion.id).score;
    if (score === null || !Number.isFinite(criterion.normalizedWeight) || criterion.normalizedWeight <= 0) continue;
    weighted += score * criterion.normalizedWeight;
    covered += criterion.normalizedWeight;
  }
  return covered > 0 ? weighted / covered : 0;
}

function LandscapeMap({ theme, pair, result, preferredId, selectedId, onSelect }: { theme: Exclude<SceneTheme, "balance">; pair: SceneResult["options"]; result: SceneResult; preferredId: string | null; selectedId: string; onSelect: (id: string) => void }) {
  const asset = art[theme] ?? art.career!;
  const preferredSide = pair.findIndex((option) => option.id === preferredId);
  return <div className="atlas-landscape" data-preferred-side={preferredSide >= 0 ? preferredSide : undefined}><div className="atlas-destinations">{pair.map((option, side) => <div className={option.id === preferredId ? "preferred" : ""} key={option.id} data-side={side}><span>{side === 0 ? "A" : "B"}</span><strong>{option.name}</strong>{option.id === preferredId && <em><CheckCircle2 size={12} />이 선택을 지지해요</em>}</div>)}</div><div className="atlas-art"><Image src={asset.src} alt={asset.alt} fill sizes="(max-width: 767px) 100vw, 820px" quality={75} loading="eager" /><i className="atlas-preference-glow" aria-hidden="true" /></div><svg className="atlas-routes" viewBox="0 0 800 125" preserveAspectRatio="none" aria-hidden="true"><path className={preferredSide === 0 ? "preferred" : ""} d="M400 109 C366 67 280 98 202 7" /><path className={preferredSide === 1 ? "preferred" : ""} d="M400 109 C434 67 520 98 598 7" /><circle cx="400" cy="109" r="7" /></svg><span className="atlas-origin"><Sparkles size={13} />지금의 나</span><div className="atlas-criterion-table"><div className="atlas-criterion-head"><strong>A · {pair[0].name}</strong><span>고민 포인트</span><strong>B · {pair[1].name}</strong></div>{result.criteria.slice(0, 4).map((criterion, index) => { const metrics = pair.map((option) => sceneMetric(result, option.id, criterion.id)); const winner = metrics[0].score === null || metrics[1].score === null || metrics[0].score === metrics[1].score ? -1 : Number(metrics[1].score) > Number(metrics[0].score) ? 1 : 0; return <button key={criterion.id} type="button" aria-pressed={criterion.id === selectedId} onClick={() => onSelect(criterion.id)}><span data-side="0" className={winner === 0 ? "winner" : ""}>{metrics[0].scoreLabel}</span><strong><i>{index + 1}</i>{criterion.name}<small>{metrics[0].weightLabel}</small></strong><span data-side="1" className={winner === 1 ? "winner" : ""}>{metrics[1].scoreLabel}</span></button>; })}</div></div>;
}

function BalanceMap({ pair, balance, preferredId, selectedId, replay, onSelect, onReplay }: { pair: SceneResult["options"]; balance: ReturnType<typeof balanceContributions>; preferredId: string | null; selectedId: string; replay: number; onSelect: (id: string) => void; onReplay: () => void }) {
  const degrees = balance.angle * 180 / Math.PI;
  const shown = balance.contributions.slice(0, 5);
  const arm = 205, radians = degrees * Math.PI / 180;
  const points = [{ x: 320 - arm * Math.cos(radians), y: 118 - arm * Math.sin(radians) }, { x: 320 + arm * Math.cos(radians), y: 118 + arm * Math.sin(radians) }];
  const weights = (side: 0 | 1) => shown.map((item, index) => { const contribution = side === 0 ? item.left : item.right; const radius = 14 + Math.sqrt(Math.max(0, contribution)) * 6; const x = [-62, -29, 4, 37, 68][index]; const y = 144 - radius - (index % 2) * 7; return <g key={`${replay}-${item.criterionId}-${side}`} className={`atlas-weight drop-${index}`} data-active={item.criterionId === selectedId} onClick={() => onSelect(item.criterionId)} role="button" tabIndex={0} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") onSelect(item.criterionId); }} aria-label={`${item.name}, ${pair[side].name} 기여도 ${Math.round(contribution * 100)}점`}><title>{item.name}</title><circle cx={x} cy={y} r={radius} /><text x={x} y={y + 6}>{index + 1}</text></g>; });
  return <div className="atlas-balance"><div className="atlas-scale-labels"><strong className={pair[0].id === preferredId ? "preferred" : ""}>{pair[0].name}{pair[0].id === preferredId && <em>지지하는 선택</em>}<small>{balance.hasData ? `${Math.round(balance.left * 100)} 가중점` : "평가 미확인"}</small></strong><strong className={pair[1].id === preferredId ? "preferred" : ""}>{pair[1].name}{pair[1].id === preferredId && <em>지지하는 선택</em>}<small>{balance.hasData ? `${Math.round(balance.right * 100)} 가중점` : "평가 미확인"}</small></strong></div><svg viewBox="0 0 640 385" role="img" aria-label="고민 포인트가 무게추로 쌓인 결정 저울"><defs><linearGradient id="atlas-metal" x1="0" x2="1"><stop stopColor="#685c7a"/><stop offset=".22" stopColor="#eee9f3"/><stop offset=".52" stopColor="#a99db8"/><stop offset=".72" stopColor="#fff"/><stop offset="1" stopColor="#6d617c"/></linearGradient><radialGradient id="atlas-violet"><stop stopColor="#eee4fb"/><stop offset="1" stopColor="#8d70b5"/></radialGradient><radialGradient id="atlas-jade"><stop stopColor="#e3f7ef"/><stop offset="1" stopColor="#68a897"/></radialGradient></defs><ellipse cx="320" cy="357" rx="118" ry="16" className="atlas-shadow"/><path d="M276 166 Q309 245 286 326 Q320 352 354 326 Q331 245 364 166Z" fill="url(#atlas-metal)"/><ellipse cx="320" cy="334" rx="86" ry="25" fill="url(#atlas-metal)"/><g className="atlas-beam" style={{ transform: `rotate(${degrees}deg)` }}><path d="M108 110 Q320 96 532 110 L532 126 Q320 113 108 126Z" fill="url(#atlas-metal)"/><circle cx="115" cy="118" r="10" fill="url(#atlas-metal)"/><circle cx="525" cy="118" r="10" fill="url(#atlas-metal)"/></g>{([0,1] as const).map((side) => <g key={side} className={`atlas-pan ${side ? "right" : "left"}`} style={{ transform: `translate(${points[side].x}px,${points[side].y}px)` }}><path d="M0 0 L-92 139 M0 0 L92 139"/><ellipse cy="146" rx="100" ry="20"/><g>{weights(side)}</g><path d="M-100 145 Q-78 204 0 203 Q78 204 100 145 Q0 178 -100 145Z" fill="url(#atlas-metal)"/></g>)}<circle cx="320" cy="118" r="39" fill="url(#atlas-metal)"/><circle cx="320" cy="118" r="25" fill="url(#atlas-violet)"/><circle cx="320" cy="118" r="10" fill="#f3ecff"/>{balance.missing.length > 0 && <g className="atlas-unknown"><rect x="469" y="324" width="42" height="44" rx="14"/><text x="490" y="354">?</text></g>}</svg><div className="atlas-scale-actions"><span>무게추 크기 = 중요도 × 상대 평가</span><button type="button" onClick={onReplay}><RotateCcw size={15} />다시 보기</button></div><div className="atlas-weight-keys">{shown.map((item, index) => <button key={item.criterionId} type="button" aria-pressed={item.criterionId === selectedId} onClick={() => onSelect(item.criterionId)}><span>{index + 1}</span>{item.name}</button>)}{balance.missing.length > 0 && <span className="missing">? · 미확인: {balance.missing.join(", ")}</span>}</div></div>;
}

function atlasDescription(theme: SceneTheme) {
  if (theme === "career") return "두 업무 지구를 오가며 성장, 안정, 시간과 실제 조건을 함께 비교해요.";
  if (theme === "travel") return "각 섬에서 얻게 될 경험과 감수할 이동·비용을 하나의 항로로 비교해요.";
  if (theme === "housing") return "두 생활권의 통근, 비용, 공간과 안전 조건을 일상의 경로로 살펴봐요.";
  if (theme === "purchase") return "지금 것을 유지할 때와 새로 선택할 때의 사용 가치와 부담을 비교해요.";
  return "마음을 움직인 여러 이유를 무게추로 놓고, 어느 쪽에 힘이 실리는지 살펴봐요.";
}
