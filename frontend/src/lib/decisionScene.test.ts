import { describe, expect, it } from "vitest";
import { balanceContributions, detectSceneTheme, sceneMetric, selectComparisonPair, weightKind } from "./decisionScene";
import { sceneExample } from "./sceneExamples";

describe("decision scene classification", () => {
  it.each(["career", "travel", "purchase", "housing", "balance"] as const)("classifies %s examples", (theme) => {
    expect(detectSceneTheme(sceneExample(theme))).toBe(theme);
  });
  it("does not infer travel or work from generic criteria", () => {
    expect(detectSceneTheme({ ...sceneExample("career"), title: "짜장면과 짬뽕", options: [{ ...sceneExample("career").options[0], name: "짜장면" }, { ...sceneExample("career").options[1], name: "짬뽕" }] })).toBe("balance");
  });
  it("uses balance for mixed intent rather than silently choosing a domain", () => {
    expect(detectSceneTheme({ ...sceneExample("career"), title: "이직 때문에 이사도 할까?" })).toBe("balance");
  });
  it("prefers explicit title intent over incidental option keywords", () => {
    expect(detectSceneTheme({ ...sceneExample("purchase"), title: "업무용 노트북을 구매할까?" })).toBe("purchase");
  });
});

describe("honest balance", () => {
  it("tilts toward the larger weighted contribution and reverses when sides swap", () => {
    const result = sceneExample("balance");
    const forward = balanceContributions(result, "option-0", "option-1");
    const reversed = balanceContributions(result, "option-1", "option-0");
    expect(forward.left).toBeCloseTo(.475);
    expect(forward.right).toBeCloseTo(.575);
    expect(forward.angle).toBeCloseTo(-.024);
    expect(reversed.angle).toBeCloseTo(-forward.angle);
  });
  it("does not use narrative encouragement as a weight", () => {
    const result = sceneExample("balance");
    const before = balanceContributions(result, "option-0", "option-1");
    result.guidance.encouragedOptionId = "option-0";
    expect(balanceContributions(result, "option-0", "option-1")).toEqual(before);
  });
  it("does not count missing scores as zero or duplicate a criterion", () => {
    const result = sceneExample("balance");
    result.assessments = result.assessments.filter((a) => !(a.optionId === "option-0" && a.criterionId === "criterion-0"));
    const balance = balanceContributions(result, "option-0", "option-1");
    expect(balance.contributions).toHaveLength(3);
    expect(balance.missing).toEqual(["휴식 필요"]);
  });
  it("distinguishes ties from absent evaluations", () => {
    const result = sceneExample("balance");
    result.assessments = result.assessments.map((a) => ({ ...a, score: 50 }));
    expect(balanceContributions(result, "option-0", "option-1")).toMatchObject({ angle: -0, hasData: true });
    result.assessments = [];
    expect(balanceContributions(result, "option-0", "option-1")).toMatchObject({ hasData: false });
  });
  it("excludes invalid scores and nonpositive weights", () => {
    const result = sceneExample("balance");
    result.assessments[0].score = NaN;
    result.assessments[1].score = 101;
    result.criteria[2].normalizedWeight = -1;
    result.criteria[3].normalizedWeight = 0;
    expect(balanceContributions(result, "option-0", "option-1").hasData).toBe(false);
  });
  it("preserves weighted proportions regardless of weight normalization", () => {
    const result = sceneExample("balance");
    const before = balanceContributions(result, "option-0", "option-1").angle;
    result.criteria.forEach((c) => c.normalizedWeight *= 100);
    expect(balanceContributions(result, "option-0", "option-1").angle).toBeCloseTo(before);
  });
});

describe("shared score-to-object mapping", () => {
  it("maps score linearly to tower height and weight times score to volume", () => {
    const result = sceneExample("career");
    const a = result.assessments[0];
    a.score = 40;
    expect(sceneMetric(result, a.optionId, a.criterionId)).toMatchObject({ scoreLabel: "40점", weightLabel: "중요도 25%", height: 1.52, contribution: .1 });
    a.score = 80;
    expect(sceneMetric(result, a.optionId, a.criterionId).height).toBeCloseTo(3.04);
  });
  it("preserves zero, excludes invalid numbers and never invents a missing assessment", () => {
    const result = sceneExample("career");
    const a = result.assessments[0];
    a.score = 0;
    expect(sceneMetric(result, a.optionId, a.criterionId)).toMatchObject({ scoreLabel: "0점", height: 0, contribution: 0 });
    for (const score of [NaN, Infinity, -1, 101]) {
      a.score = score;
      expect(sceneMetric(result, a.optionId, a.criterionId)).toMatchObject({ scoreLabel: "미확인", height: null, contribution: null });
    }
    expect(sceneMetric(result, "absent", a.criterionId).scoreLabel).toBe("미확인");
  });
  it("does not alter scores to match the encouraged option", () => {
    const result = sceneExample("career"), a = result.assessments[0];
    const before = sceneMetric(result, a.optionId, a.criterionId);
    result.guidance.encouragedOptionId = result.options[1].id;
    expect(sceneMetric(result, a.optionId, a.criterionId)).toEqual(before);
  });
  it("does not display invalid importance as a real weight", () => {
    const result = sceneExample("career"), a = result.assessments[0];
    result.criteria[0].normalizedWeight = NaN;
    expect(sceneMetric(result, a.optionId, a.criterionId)).toMatchObject({ weight: null, contribution: null, weightLabel: "중요도 미확인" });
  });
});

describe("comparison and weight shapes", () => {
  it("supports selecting among eight options without duplicate or stale IDs", () => {
    const result = sceneExample("career");
    result.options = Array.from({ length: 8 }, (_, i) => ({ ...result.options[0], id: `id-${i}` }));
    expect(selectComparisonPair(result, ["id-7", "id-6"]).map((o) => o.id)).toEqual(["id-7", "id-6"]);
    expect(selectComparisonPair(result, ["id-7", "id-7"]).map((o) => o.id)).toEqual(["id-7", "id-0"]);
    expect(selectComparisonPair(result, ["stale", "stale"]).map((o) => o.id)).toEqual(["id-0", "id-1"]);
  });
  it("handles zero or one option", () => {
    const result = sceneExample("balance");
    result.options = []; expect(selectComparisonPair(result, [])).toEqual([]);
    result.options = [sceneExample("balance").options[0]]; expect(selectComparisonPair(result, [])).toHaveLength(1);
  });
  it.each([["예산", "coin"], ["통근 시간", "clock"], ["가족과 안정", "home"], ["휴식", "heart"], ["새로운 경험", "spark"]])("maps %s to %s", (name, kind) => {
    expect(weightKind(name)).toBe(kind);
  });
});
