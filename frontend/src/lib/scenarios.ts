export type Scenario = {
  category: string;
  prompt: string;
  description: string;
  accent: string;
};

export const scenarios: Scenario[] = [
  {
    category: "주거",
    prompt: "아이 때문에 이사할지, 지금 집에 계속 살지 고민돼.",
    description: "이사, 매매, 전월세처럼 가족의 생활 조건을 함께 비교해요.",
    accent: "violet",
  },
  {
    category: "여행",
    prompt: "이번 휴가에 제주도와 일본 중 어디로 갈지 고민돼.",
    description: "예산과 일정, 동행자의 취향을 한눈에 정리해요.",
    accent: "blue",
  },
  {
    category: "커리어",
    prompt: "지금 회사에 남을지 새로운 회사로 이직할지 고민돼.",
    description: "성장, 안정성, 보상과 일하는 방식을 균형 있게 살펴봐요.",
    accent: "indigo",
  },
  {
    category: "구매",
    prompt: "새 노트북을 살지 지금 쓰는 제품을 계속 쓸지 고민돼.",
    description: "비용뿐 아니라 사용 빈도와 실제 불편함까지 비교해요.",
    accent: "cyan",
  },
  {
    category: "학습",
    prompt: "AI를 공부하려는데 Python부터 할지 LLM부터 할지 고민돼.",
    description: "현재 수준, 목표, 시간에 맞는 학습 경로를 구조화해요.",
    accent: "purple",
  },
  {
    category: "비즈니스",
    prompt: "다음 개발 기능으로 A와 B 중 무엇을 먼저 만들지 고민돼.",
    description: "사용자 가치, 개발 비용과 전략적 중요도를 함께 봐요.",
    accent: "sky",
  },
];

