import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { DecisionMapGraph } from "./DecisionMapGraph";
import { ResultStage } from "./DecisionWorkspace";
import { sceneExample } from "@/lib/sceneExamples";

describe("decision world integration", () => {
  it("fixes the theme from the question and offers no theme switch", () => {
    const html = renderToStaticMarkup(<DecisionMapGraph result={sceneExample("career")} />);
    expect(html).toContain("질문에 맞춘 지도");
    expect(html).not.toContain("화면 유형");
    expect(html).not.toContain("world-themes");
    expect(html).not.toContain("<select");
  });
  it("renders every criterion and both scores at once, including missing data", () => {
    const result = sceneExample("career");
    result.assessments.shift();
    const html = renderToStaticMarkup(<DecisionMapGraph result={result} />);
    expect(html).toContain("모든 고민 포인트의 점수 비교");
    for (const criterion of result.criteria) expect(html).toContain(criterion.name);
    expect(html).toContain("미확인");
    expect(html).toContain("80점");
    expect(html).toContain("중요도 25%");
    expect(html).not.toContain("크기는 점수나");
  });
  it("renders actual result criteria and evidence without requiring WebGL on the server", () => {
    const result = sceneExample("career");
    result.assessments[0].reason = "직접 입력한 고유한 평가 이유";
    const html = renderToStaticMarkup(<DecisionMapGraph result={result} />);
    expect(html).toContain("나의 결정 도시");
    expect(html).toContain("직접 입력한 고유한 평가 이유");
    expect(html).toContain("사용자 가정");
    expect(html).toContain("간단히 보기 (2D)");
    expect(html).toContain("외부 검증된 사실과는 다릅니다");
  });
  it("mounts the typed map in real result view and removes the stale score-color legend", () => {
    const html = renderToStaticMarkup(<ResultStage result={sceneExample("travel")} activeTab="map" setActiveTab={() => {}} />);
    expect(html).toContain("가능성을 잇는 여행 지도");
    expect(html).not.toContain("70점 이상");
    expect(html).toContain("제주에서 휴식");
  });
  it("keeps empty and one-option results readable", () => {
    const result = sceneExample("balance");
    result.options = []; result.criteria = []; result.assessments = []; result.optionProfiles = [];
    expect(renderToStaticMarkup(<DecisionMapGraph result={result} />)).toContain("선택지 미확인");
    result.options = [sceneExample("balance").options[0]];
    expect(renderToStaticMarkup(<DecisionMapGraph result={result} />)).toContain("비교 가능한 공통 평가가 없어");
  });
});
