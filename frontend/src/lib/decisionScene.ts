import type { DecisionResultResponse } from "./api";

export type SceneResult = DecisionResultResponse["result"];
export type SceneTheme = "career" | "travel" | "balance" | "purchase" | "housing";
export const sceneThemes: Record<SceneTheme, { label: string; title: string; description: string }> = {
  career: { label: "커리어 · 도시", title: "나의 결정 도시", description: "선택지는 도시 구역, 판단 기준은 빌딩입니다. 빛이 흐르는 도로를 따라 각 대안의 이유를 살펴보세요." },
  travel: { label: "여행 · 섬", title: "가능성을 잇는 여행 지도", description: "선택지는 섬, 판단 기준은 여행의 이정표입니다. 실제 지리·거리·항로를 나타내는 지도는 아닙니다." },
  balance: { label: "기타 · 저울", title: "나에게 중요한 것의 균형", description: "기준의 중요도와 상대 평가가 무게추로 쌓입니다. 기울기는 선호의 요약이며 정답이나 성공 확률이 아닙니다." },
  purchase: { label: "구매 · 쇼룸", title: "내 생활에 맞는 선택", description: "대안별 진열대에서 사용 가치와 부담을 비교하세요. 모형은 상징이며 실제 제품의 사양을 나타내지 않습니다." },
  housing: { label: "주거 · 생활권", title: "내 일상이 머무는 곳", description: "집과 생활 경로를 따라 주거 조건을 비교하세요. 실제 위치나 통근 거리를 나타내지는 않습니다." },
};

// Titles carry intent; generic criteria (cost, stability, growth) must not select a theme.
const patterns: Record<Exclude<SceneTheme, "balance">, RegExp> = {
  career: /이직|퇴사|취업|커리어|직무|직장(?!\s*근처)|승진|전직|창업|\b(career|job|resign)\b/i,
  travel: /여행|여행지|휴가|관광|휴양|신혼여행|\b(travel|vacation|trip)\b/i,
  purchase: /구매|구입|수리|중고|노트북|스마트폰|자동차|가전|살까|살지|\b(buy|purchase|repair)\b/i,
  housing: /이사|주거|전세|월세|매매|집을|집에|아파트|통근|\b(housing|relocat|rent)\b/i,
};

export function detectSceneTheme(result: Pick<SceneResult, "title" | "options">): SceneTheme {
  const matches = (text: string) => (Object.keys(patterns) as Array<keyof typeof patterns>).filter((key) => patterns[key].test(text));
  const title = matches(result.title);
  if (title.length === 1) return title[0];
  if (title.length > 1) return "balance"; // Mixed intent: let the user choose instead of guessing.
  const options = matches(result.options.map((o) => o.name).join(" "));
  return options.length === 1 ? options[0] : "balance";
}

export type WeightKind = "coin" | "clock" | "heart" | "home" | "spark";
export function weightKind(name: string): WeightKind {
  if (/비용|가격|예산|연봉|보상|돈|금액/.test(name)) return "coin";
  if (/시간|기간|통근|거리|일정/.test(name)) return "clock";
  if (/안정|주거|공간|안전|가족/.test(name)) return "home";
  if (/만족|행복|휴식|마음|건강|관계/.test(name)) return "heart";
  return "spark";
}

export type BalanceContribution = { criterionId: string; name: string; weight: number; left: number; right: number; kind: WeightKind };
export function balanceContributions(result: SceneResult, leftId: string, rightId: string) {
  const contributions: BalanceContribution[] = [];
  const missing: string[] = [];
  for (const criterion of result.criteria) {
    const left = result.assessments.find((a) => a.optionId === leftId && a.criterionId === criterion.id);
    const right = result.assessments.find((a) => a.optionId === rightId && a.criterionId === criterion.id);
    const weight = criterion.normalizedWeight;
    if (!left || !right || !Number.isFinite(left.score) || !Number.isFinite(right.score) || left.score < 0 || left.score > 100 || right.score < 0 || right.score > 100 || !Number.isFinite(weight) || weight <= 0 || leftId === rightId) {
      missing.push(criterion.name);
      continue;
    }
    contributions.push({ criterionId: criterion.id, name: criterion.name, weight, left: weight * left.score / 100, right: weight * right.score / 100, kind: weightKind(criterion.name) });
  }
  const totalWeight = contributions.reduce((sum, c) => sum + c.weight, 0);
  const left = contributions.reduce((sum, c) => sum + c.left, 0);
  const right = contributions.reduce((sum, c) => sum + c.right, 0);
  // Equal arms. Positive difference means the right pan lowers. No encouragement bonus.
  const difference = totalWeight ? (right - left) / totalWeight : 0;
  return { contributions, missing, left, right, angle: -Math.max(-1, Math.min(1, difference)) * 0.24, hasData: contributions.length > 0 };
}

export function selectComparisonPair(result: SceneResult, requested: readonly string[]) {
  const first = result.options.find((o) => o.id === requested[0]) ?? result.options[0];
  const second = result.options.find((o) => o.id === requested[1] && o.id !== first?.id) ?? result.options.find((o) => o.id !== first?.id);
  return [first, second].filter((o): o is SceneResult["options"][number] => Boolean(o));
}
