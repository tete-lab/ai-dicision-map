import { type SceneResult, type SceneTheme } from "./decisionScene";

// Explicitly fictional, local-only examples for the preview. Never saved or submitted to an LLM.
const examples: Record<SceneTheme, { title: string; options: string[]; criteria: string[]; reasons: string[][]; pros: string[][]; cons: string[][] }> = {
  career: { title: "지금 회사에 남을까, 이직을 탐색할까?", options: ["현재 직장 유지", "재직하며 이직 탐색"], criteria: ["성장 기회", "생활 안정", "적응 부담", "시간 여유"], reasons: [["현재 맡은 업무가 익숙하지만 새 역할의 기회는 확인이 필요해요.", "익숙한 생활 리듬을 유지하고 싶어요.", "새 환경에 적응하는 부담을 줄일 수 있어요.", "별도 구직 활동 시간을 줄일 수 있어요."], ["새로운 역할을 맡아보고 싶다는 기대가 있어요.", "재직을 유지하며 실제 제안 조건을 확인하고 싶어요.", "온보딩과 팀 문화를 아직 확인하지 못했어요.", "퇴근 후 탐색에 쓸 시간을 확보해야 해요."]], pros: [["익숙한 업무와 생활 유지", "내부 역할 변경을 논의할 수 있음"], ["새로운 역할의 가능성을 확인", "제안을 받은 뒤 조건 비교 가능"]], cons: [["역할이 바뀌지 않으면 정체감이 남을 수 있음"], ["탐색에 시간과 에너지가 필요", "원하는 제안을 얻는다는 보장은 없음"]] },
  travel: { title: "이번 휴가는 제주 여행과 오사카 여행 중 어디로 갈까?", options: ["제주에서 휴식", "오사카 도시 탐방"], criteria: ["예산", "이동 시간", "새로운 경험", "휴식"], reasons: [["숙소와 교통을 포함한 총예산을 확인해야 해요.", "출발지에서 실제 이동 시간을 비교해야 해요.", "자연을 즐기는 일정을 생각하고 있어요.", "여유 있는 일정에 마음이 가요."], ["항공과 현지 지출을 포함한 예산 확인이 필요해요.", "공항과 숙소까지 이동 시간을 확인해야 해요.", "새로운 도시의 음식과 거리를 경험하고 싶어요.", "일정이 빡빡하면 피로할 수 있어요."]], pros: [["여유 있는 자연 중심 일정 가능"], ["새로운 도시 경험에 대한 기대"]], cons: [["날씨와 이동 수단 확인 필요"], ["총비용과 이동 부담 확인 필요"]] },
  balance: { title: "주말에 쉴까, 새로운 모임에 갈까?", options: ["주말에 쉬기", "새로운 모임 가기"], criteria: ["휴식 필요", "혼자만의 시간", "새로운 경험", "사람 만나기"], reasons: [["한 주의 피로를 풀 시간이 필요해요.", "혼자 조용히 보낼 시간을 원해요.", "집에서 새로운 활동을 해볼 수도 있어요.", "이번 주에는 만남의 기회가 줄어요."], ["참여 시간을 짧게 정할 수 있는지 확인해야 해요.", "모임 전후 혼자 쉴 시간을 남길 수 있어요.", "새로운 활동을 경험해보고 싶어요.", "새 사람들과 이야기할 생각에 기대가 돼요."]], pros: [["피로 회복을 위한 시간을 확보"], ["새로운 활동과 만남을 경험할 기회"]], cons: [["기대하던 경험을 미룰 수 있음"], ["이동과 참여로 피로가 늘 수 있음"]] },
  purchase: { title: "업무용 노트북을 구매할까, 현재 기기를 수리할까?", options: ["새 노트북 구매", "현재 노트북 수리"], criteria: ["총비용", "업무 안정성", "사용 기간", "수리 가능성"], reasons: [["구매 예산의 상한을 먼저 정해야 해요.", "업무 프로그램에 맞는 사양을 확인해야 해요.", "보증과 지원 기간을 확인해야 해요.", "기존 장비의 데이터를 옮길 방법이 필요해요."], ["정확한 수리 견적을 받아봐야 해요.", "수리 후 같은 문제가 반복되는지 확인해야 해요.", "남은 사용 기간은 아직 알 수 없어요.", "수리 가능 여부와 기간을 문의해야 해요."]], pros: [["업무 요구에 맞춘 사양 선택 가능"], ["현재 장비를 계속 활용할 가능성"]], cons: [["초기 지출과 데이터 이전 부담"], ["견적·수리 기간·보증 확인 필요"]] },
  housing: { title: "직장 근처로 이사할까, 지금 집에 남을까?", options: ["직장 근처로 이사", "지금 집 유지"], criteria: ["주거비", "통근 시간", "생활 공간", "생활 안정"], reasons: [["월세와 관리비, 이사비를 함께 확인해야 해요.", "실제 출퇴근 시간대 이동 시간을 확인하고 싶어요.", "새 집의 수납과 공간이 충분한지 봐야 해요.", "주변 생활환경은 아직 확인하지 못했어요."], ["현재 비용 수준을 유지할 수 있어요.", "현재 통근 부담이 계속될 수 있어요.", "익숙한 공간을 그대로 사용할 수 있어요.", "생활권을 바꾸는 부담을 피할 수 있어요."]], pros: [["통근 부담을 줄일 가능성"], ["익숙한 집과 생활권 유지"]], cons: [["주거비·계약·생활환경 확인 필요"], ["현재 통근 부담 지속 가능"]] },
};

export function sceneExample(theme: SceneTheme): SceneResult {
  const e = examples[theme];
  const criteria: SceneResult["criteria"] = e.criteria.map((name, i) => ({ id: `criterion-${i}`, name, normalizedWeight: .25, sourceType: "USER_ASSUMPTION" }));
  return {
    sessionId: `preview-${theme}`, title: e.title,
    options: e.options.map((name, i) => ({ id: `option-${i}`, name, score: null, rank: null, summary: null, tiedForLead: false })), criteria,
    assessments: e.options.flatMap((_, option) => criteria.map((c, i) => ({ criterionId: c.id, optionId: `option-${option}`, score: option === 0 ? [60, 70, 30, 30][i] : [40, 30, 80, 80][i], reason: e.reasons[option][i], sourceType: "USER_ASSUMPTION" as const }))),
    insights: [], actionPlan: [], decisionRules: [], scenarioForecasts: [],
    completeness: { expectedScoreCount: 8, providedScoreCount: 8, missingScoreCount: 0, scoreCoveragePercent: 100, weightedCoveragePercent: 100, missingAssessments: [] },
    narrativeStatus: "READY", guidance: { encouragedOptionId: null, basis: "NEEDS_VERIFICATION", headline: "시각화를 위한 가상 예시", rationale: "", encouragement: "", confidence: "LOW" },
    optionProfiles: e.options.map((_, i) => ({ optionId: `option-${i}`, pros: e.pros[i], cons: e.cons[i], evidenceRefs: [], bestWhen: "가상 예시: 실제 조건을 확인한 뒤 판단하세요." })),
    evidenceQuality: { level: "LOW", factCount: 0, assumptionCount: 8, inferenceCount: 0, reasonedAssessmentCount: 8 },
  };
}
