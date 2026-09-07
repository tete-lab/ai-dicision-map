import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import type { DecisionResultResponse } from "@/lib/api";
import { ResultStage } from "./DecisionWorkspace";

vi.mock("./DecisionMapGraph", () => ({ DecisionMapGraph: () => null }));

const result: DecisionResultResponse["result"] = {
  sessionId: "sample", title: "업무용 기기를 구매할까",
  options: [{ id: "buy", name: "구매", score: 80, rank: 1, summary: null, tiedForLead: false },
    { id: "wait", name: "보류", score: 20, rank: 2, summary: null, tiedForLead: false }],
  criteria: [{ id: "cost", name: "비용", normalizedWeight: 1, sourceType: "USER_ASSUMPTION" }],
  assessments: [{ criterionId: "cost", optionId: "buy", score: 80, sourceType: "USER_ASSUMPTION", reason: "구매 80점 보류 20점이라 유리함" }],
  insights: [], actionPlan: [], decisionRules: [], scenarioForecasts: [],
  completeness: { expectedScoreCount: 2, providedScoreCount: 2, missingScoreCount: 0, scoreCoveragePercent: 100, weightedCoveragePercent: 100, missingAssessments: [] },
  narrativeStatus: "READY",
  guidance: { encouragedOptionId: "buy", basis: "CONTEXTUAL", headline: "총비용을 확인한 뒤 구매를 검토해보세요.",
    rationale: "고장 난 기기를 대체하면 매일 필요한 업무를 재개할 수 있어요.", encouragement: "조건을 하나씩 확인해봐요.",
    confidence: "MEDIUM", nextAction: "수리 견적과 구매 총비용을 비교하세요.", practicalAlternative: "수리가 가능한지 먼저 확인해보세요.",
    evidenceRefs: ["업무용 기기가 고장났어요."] },
  optionProfiles: [{ optionId: "buy", bestWhen: "업무 재개가 시급하고 총비용을 감당할 수 있을 때", pros: ["업무 재개 가능"], cons: ["총비용 확인 필요"] },
    { optionId: "wait", bestWhen: "기존 기기를 수리할 수 있을 때", pros: ["지출 보류"], cons: ["업무 지연 가능"] }],
  evidenceQuality: { level: "LOW", factCount: 1, assumptionCount: 2, inferenceCount: 0, reasonedAssessmentCount: 2 },
};

describe("recommendation-first results", () => {
  it("shows practical alternatives and context before optional scores without repeating score reasons", () => {
    const html = renderToStaticMarkup(<ResultStage result={result} activeTab="compare" setActiveTab={() => {}} />);
    expect(html).toContain("수리가 가능한지 먼저 확인해보세요.");
    expect(html).toContain("업무용 기기가 고장났어요.");
    expect(html.indexOf("수리 견적")).toBeLessThan(html.indexOf('class="score-details"'));
    expect(html).toContain('<details class="score-details">');
    expect(html).not.toContain("구매 80점 보류 20점이라 유리함");
    expect(html).not.toContain("응원 방향 점수");
  });

  it("does not present unavailable AI analysis as a completed recommendation", () => {
    const html = renderToStaticMarkup(<ResultStage result={{ ...result, narrativeStatus: "FALLBACK", narrativeError: "LLM_NOT_CONFIGURED" }} activeTab="action" setActiveTab={() => {}} onRetry={() => {}} />);
    expect(html).toContain("맞춤 AI 분석이 아직 완료되지 않았어요.");
    expect(html).toContain("실제 LLM_API_KEY");
    expect(html).toContain("맞춤 AI 분석 다시 시도");
    expect(html).toContain("기본 점검 안내");
  });

  it("protects mobile focus and contains overflow without disabling user zoom", () => {
    const css = readFileSync(new URL("../../app/globals.css", import.meta.url), "utf8");
    const component = readFileSync(new URL("./DecisionWorkspace.tsx", import.meta.url), "utf8");
    expect(css).toContain(".scenario-forecast select { font-size: 16px; }");
    expect(css).toContain(".score-details { min-width: 0; max-width: 100%; }");
    expect(component).not.toContain("scrollIntoView");
    expect(component).toContain("focus({ preventScroll: true })");
    expect(component).toContain("editableWhileLoading");
    expect(component).toContain("collection-dock");
    expect(css).toContain(".workspace.stage-2 { height: calc(100dvh - 60px)");
    expect(css).toContain(".stage-2 .collection-dock .composer { position: sticky");
    const layout = readFileSync(new URL("../../app/layout.tsx", import.meta.url), "utf8");
    expect(layout).not.toMatch(/userScalable:\s*false|maximumScale:\s*1/);
  });
});
