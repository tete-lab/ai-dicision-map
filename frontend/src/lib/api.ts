export type HealthResponse = {
  status: "UP";
  service: string;
};

export type DecisionStage =
  | "STARTED"
  | "IDENTIFYING_OPTIONS"
  | "COLLECTING_CRITERIA"
  | "COLLECTING_INFORMATION"
  | "READY_TO_ANALYZE"
  | "ANALYZED";

export type DecisionSourceType =
  | "USER_FACT"
  | "USER_ASSUMPTION"
  | "AI_INFERENCE";

export type DecisionState = {
  decisionTitle: string;
  summary: string;
  stage: DecisionStage;
  options: Array<{ id: string; name: string }>;
  criteria: Array<{
    id: string;
    name: string;
    weight: number;
    sourceType: DecisionSourceType;
    confidence: number;
  }>;
  knownFacts: string[];
  assumptions: string[];
  aiInferences: string[];
  missingInformation: string[];
  askedQuestions: string[];
  userLeaningOptionId: string | null;
  userLeaningEvidence: string[];
  progress: number;
  readyToAnalyze: boolean;
  nextQuestion: string;
};

export type DecisionTurnResponse = {
  sessionId: string;
  assistantMessage: string;
  suggestionMode: "SINGLE" | "ORDERED";
  suggestedAnswers: SuggestedAnswer[];
  state: DecisionState;
  responseMode?: "AI" | "GUIDED" | "FALLBACK";
};

export type SuggestedAnswer = { label: string; value: string };

export type DecisionStateResponse = {
  sessionId: string;
  state: DecisionState;
};

export type CriterionOptionAssessment = {
  criterionId: string;
  optionId: string;
  score: number;
  reason?: string;
  sourceType: DecisionSourceType;
};

export type DecisionResultResponse = {
  status: "ANALYZED";
  result: {
    sessionId: string;
    title: string;
    options: Array<{
      id: string;
      name: string;
      summary: string | null;
      score: number | null;
      rank: number | null;
      tiedForLead: boolean;
    }>;
    criteria: Array<{
      id: string;
      name: string;
      normalizedWeight: number;
      sourceType: DecisionSourceType;
    }>;
    assessments: Array<CriterionOptionAssessment>;
    insights: Array<{
      type: "KEY_DRIVER" | "TRADE_OFF" | "RISK" | "MISSING_INFORMATION";
      title: string;
      content: string;
      priority: number;
      evidenceRefs: string[];
      assumptionRefs: string[];
      confidence: "HIGH" | "MEDIUM" | "LOW";
      whatCouldChange: string;
    }>;
    completeness: {
      expectedScoreCount: number;
      providedScoreCount: number;
      missingScoreCount: number;
      scoreCoveragePercent: number;
      weightedCoveragePercent: number;
      missingAssessments: Array<{ optionId: string; criterionId: string }>;
    };
    narrativeStatus: "PENDING" | "READY" | "FALLBACK";
    guidance: {
      encouragedOptionId: string | null;
      basis: "USER_LEANING_SUPPORTED" | "SCORE_LEADER" | "TIE";
      headline: string;
      rationale: string;
      encouragement: string;
      confidence: "HIGH" | "MEDIUM" | "LOW";
    };
    optionProfiles: Array<{ optionId: string; pros: string[]; cons: string[] }>;
    scenarioForecasts: Array<{
      criterionId: string;
      criterionName: string;
      currentWeightPercent: number;
      points: Array<{ weightPercent: number; optionScores: Record<string, number> }>;
    }>;
    evidenceQuality: {
      level: "HIGH" | "MEDIUM" | "LOW";
      factCount: number;
      assumptionCount: number;
      inferenceCount: number;
      reasonedAssessmentCount: number;
    };
    actionPlan: Array<{
      order: number;
      timing: string;
      task: string;
      why: string;
      evidenceNeeded: string[];
      doneWhen: string;
    }>;
    decisionRules: Array<{ outcome: "SELECT" | "REASSESS" | "PAUSE"; condition: string }>;
  };
};

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

const defaultApiBaseUrl = "http://localhost:8013";

export function buildApiUrl(path: string, baseUrl = process.env.NEXT_PUBLIC_API_BASE_URL) {
  const normalizedBase = (baseUrl ?? defaultApiBaseUrl).replace(/\/$/, "");
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${normalizedBase}${normalizedPath}`;
}

export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const response = await fetch(buildApiUrl("/api/v1/health"), {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
    signal,
  });

  if (!response.ok) {
    throw new Error(`Health request failed with status ${response.status}`);
  }

  return response.json() as Promise<HealthResponse>;
}

async function requestJson<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(buildApiUrl(path), {
    ...init,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...init.headers,
    },
  });

  if (!response.ok) {
    const errorBody = (await response.json().catch(() => null)) as
      | { code?: string; message?: string }
      | null;
    throw new ApiRequestError(
      errorBody?.message ?? `요청에 실패했습니다. (${response.status})`,
      response.status,
      errorBody?.code,
    );
  }

  return response.json() as Promise<T>;
}

export function createDecision(message: string): Promise<DecisionTurnResponse> {
  return requestJson("/api/v1/decisions", {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}

export function sendDecisionMessage(
  sessionId: string,
  message: string,
): Promise<DecisionTurnResponse> {
  return requestJson(`/api/v1/decisions/${encodeURIComponent(sessionId)}/messages`, {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}

export function getDecisionState(
  sessionId: string,
  signal?: AbortSignal,
): Promise<DecisionStateResponse> {
  return requestJson(`/api/v1/decisions/${encodeURIComponent(sessionId)}/state`, {
    method: "GET",
    cache: "no-store",
    signal,
  });
}

export function analyzeDecision(
  sessionId: string,
  assessments: CriterionOptionAssessment[],
): Promise<DecisionResultResponse> {
  return requestJson(`/api/v1/decisions/${encodeURIComponent(sessionId)}/analyze`, {
    method: "POST",
    body: JSON.stringify({ assessments }),
  });
}

export function enrichDecisionResult(sessionId: string): Promise<DecisionResultResponse> {
  return requestJson(`/api/v1/decisions/${encodeURIComponent(sessionId)}/enrich`, {
    method: "POST",
  });
}

export function getDecisionResult(
  sessionId: string,
  signal?: AbortSignal,
): Promise<DecisionResultResponse> {
  return requestJson(`/api/v1/decisions/${encodeURIComponent(sessionId)}/result`, {
    method: "GET",
    cache: "no-store",
    signal,
  });
}
