import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { DecisionMapGraph } from "./DecisionMapGraph";
import { ResultStage } from "./DecisionWorkspace";
import { sceneExample } from "@/lib/sceneExamples";

describe("decision world integration", () => {
  it("fixes the theme from the question and offers no theme switch", () => {
    const html = renderToStaticMarkup(<DecisionMapGraph result={sceneExample("career")} />);
    expect(html).toContain("DECISION ATLAS · 선택이 만드는 변화");
    expect(html).not.toContain("화면 유형");
    expect(html).not.toContain("world-themes");
    expect(html).not.toContain("<select");
  });
  it("renders every criterion and both scores at once, including missing data", () => {
    const result = sceneExample("career");
    result.assessments.shift();
    const html = renderToStaticMarkup(<DecisionMapGraph result={result} />);
    expect(html).toContain("고민 포인트");
    for (const criterion of result.criteria) expect(html).toContain(criterion.name);
    expect(html).toContain("미확인");
    expect(html).toContain("80점");
    expect(html).toContain("중요도 25%");
    expect(html).not.toContain("크기는 점수나");
  });
  it("renders actual result criteria and evidence without requiring WebGL on the server", () => {
    const result = sceneExample("career");
    result.guidance.encouragedOptionId = result.options[1].id;
    result.assessments[0].reason = "직접 입력한 고유한 평가 이유";
    const html = renderToStaticMarkup(<DecisionMapGraph result={result} />);
    expect(html).toContain("나의 결정 도시");
    expect(html).toContain("추천 결론");
    expect(html).toContain("재직하며 이직 탐색");
    expect(html).toContain("쪽을 지지해요");
    expect(html).toContain("이 선택을 지지해요");
    expect(html).toContain("고민 포인트");
    expect(html).toContain("직접 입력한 고유한 평가 이유");
    expect(html).toContain("사용자 가정");
    expect(html).toContain(encodeURIComponent("/decision-atlas/career.webp"));
    expect(html).not.toContain("결정 지도를 준비하고 있어요");
    expect(html).toContain("외부 검증된 사실과는 다릅니다");
  });
  it("mounts the typed map in real result view and removes the stale score-color legend", () => {
    const html = renderToStaticMarkup(<ResultStage result={sceneExample("travel")} activeTab="map" setActiveTab={() => {}} />);
    expect(html).toContain("가능성을 잇는 여행 지도");
    expect(html).not.toContain("70점 이상");
    expect(html).toContain("제주에서 휴식");
  });
  it("uses a dedicated stored atlas asset for each visual theme", () => {
    for (const theme of ["career", "travel", "housing", "purchase"] as const) {
      const html = renderToStaticMarkup(<DecisionMapGraph result={sceneExample(theme)} />);
      expect(html).toContain(encodeURIComponent(`/decision-atlas/${theme}.webp`));
      expect(html).not.toContain("<canvas");
    }
  });
  it("renders the neutral choice as a data-driven balance with multiple weights", () => {
    const html = renderToStaticMarkup(<DecisionMapGraph result={sceneExample("balance")} />);
    expect(html).toContain("고민 포인트가 무게추로 쌓인 결정 저울");
    expect(html).toContain("무게추 크기 = 중요도 × 상대 평가");
    expect(html).toContain("다시 보기");
  });
  it("keeps empty and one-option results readable", () => {
    const result = sceneExample("balance");
    result.options = []; result.criteria = []; result.assessments = []; result.optionProfiles = [];
    expect(renderToStaticMarkup(<DecisionMapGraph result={result} />)).toContain("비교할 선택지가 더 필요해요");
    result.options = [sceneExample("balance").options[0]];
    expect(renderToStaticMarkup(<DecisionMapGraph result={result} />)).toContain("비교할 선택지가 더 필요해요");
  });
});
