"use client";

import { FormEvent, KeyboardEvent as ReactKeyboardEvent, useEffect, useRef, useState } from "react";
import { ArrowRight, Bot, Check, CheckCircle2, Circle, CircleDot, Compass, Download, Lightbulb, Map, MessageCircleMore, RotateCcw, Save, Send, Share2, Sparkles, UserRound } from "lucide-react";
import {
  analyzeDecision,
  ApiRequestError,
  createDecision,
  enrichDecisionResult,
  type CriterionOptionAssessment,
  type DecisionResultResponse,
  type DecisionState as ApiDecisionState,
  type SuggestedAnswer,
  getHealth,
  sendDecisionMessage,
} from "@/lib/api";
import { scenarios } from "@/lib/scenarios";
import { withResponsePacing } from "@/lib/responseTiming";
import { DecisionMapGraph } from "./DecisionMapGraph";

type Stage = 1 | 2 | 3 | 4;
type ApiStatus = "checking" | "online" | "offline";
type ResultTab = "map" | "compare" | "insight" | "action";
type DecisionResult = DecisionResultResponse["result"];
type ChatMessage = { id: string; role: "user" | "assistant"; content: string; status?: "sending" | "sent" | "failed" };
type SuggestionConfig = { mode: "single" | "priority"; label: string; options: SuggestedAnswer[] };

const steps: Array<{ number: Stage; title: string; subtitle: string }> = [
  { number: 1, title: "질문하기", subtitle: "대화 시작" },
  { number: 2, title: "정보 수집", subtitle: "AI 질문 & 답변" },
  { number: 3, title: "분석 중", subtitle: "결정 엔진" },
  { number: 4, title: "결과 보기", subtitle: "결정 지도" },
];
const analysisItems = ["입력과 선택지 확인", "선호 경향 계산", "AI가 대안과 근거 검토", "맞춤 조언 정리 중"];
const resultTabs: Array<{ id: ResultTab; label: string }> = [
  { id: "map", label: "결정 지도" }, { id: "compare", label: "비교 요약" }, { id: "insight", label: "AI 인사이트" }, { id: "action", label: "액션 플랜" },
];

function messageId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function toConversationError(error: unknown) {
  if (error instanceof ApiRequestError) {
    if (error.code === "LLM_NOT_CONFIGURED") return "AI API 키가 설정되지 않았습니다. 백엔드의 LLM_API_KEY를 확인해주세요.";
    if (error.code === "LLM_RESPONSE_ERROR") return "답변은 안전하게 저장했어요. 잠시 후 다시 이어서 질문해볼게요.";
    return error.message;
  }
  return "서비스에 연결하지 못했습니다. 백엔드와 네트워크 상태를 확인한 뒤 다시 시도해주세요.";
}

function getSuggestionConfig(question: string, state: ApiDecisionState | null): SuggestionConfig {
  const answers = (values: string[]): SuggestedAnswer[] => values.slice(0, 3).map((value) => ({ label: value, value })).concat({ label: "기타 · 직접 입력", value: "__custom__" });
  const normalized = question.replace(/\s/g, "");
  const topic = `${state?.decisionTitle ?? ""} ${state?.options.map((option) => option.name).join(" ") ?? ""}`;
  const food = /점심|메뉴|먹을지|짜장|짬뽕|탕수육/.test(topic);
  if (/예산|비용|가격|금액|자금/.test(normalized)) return { mode: "single", label: "비용에 대한 생각을 골라보세요", options: answers(food ? ["가격 차이가 작아요", "저렴한 쪽이 좋아요", "맛이 더 중요해요"] : ["예산 한도가 있어요", "비용 차이가 작아요", "다른 조건이 더 중요해요"]) };
  if (/우선순위|중요한기준|가장중요|순서/.test(normalized)) {
    const collected = state?.criteria.map((item) => item.name) ?? [];
    const defaults = food ? ["맛과 맵기", "가격", "양과 포만감"] : /이직|회사|직장/.test(topic) ? ["성장 기회", "연봉과 보상", "업무 환경과 균형"] : /이사|주거|전학/.test(topic) ? ["통학·통근 시간", "주거비", "공간과 생활 환경"] : ["비용", "시간", "만족도"];
    return { mode: "priority", label: "중요한 순서대로 눌러주세요", options: answers(Array.from(new Set([...collected, ...defaults]))) };
  }
  if (/언제|기간|시기|기한/.test(normalized)) return { mode: "single", label: "생각 중인 시기를 골라보세요", options: answers(["1개월 이내", "3개월 이내", "6개월 이내"]) };
  if (/선택지|후보|어떤방법/.test(normalized) && state?.options.length) return { mode: "single", label: "가까운 선택지를 골라보세요", options: answers(state.options.map((item) => item.name)) };
  if (/이유|계기|고민하게/.test(normalized)) {
    const values = /이직|회사|직장/.test(state?.decisionTitle ?? "") ? ["성장 기회", "연봉과 보상", "업무 환경과 균형"] : ["시간을 줄이고 싶어서", "비용 부담을 낮추려고", "생활 환경을 바꾸고 싶어서"];
    return { mode: "single", label: "지금 마음과 가까운 이유를 골라보세요", options: answers(values) };
  }
  return { mode: "single", label: "질문에 가까운 답을 골라보세요", options: answers(["비용이 가장 중요해요", "시간이 가장 중요해요", "만족감이 가장 중요해요"]) };
}

function ensureAssessmentScores(state: ApiDecisionState, current: Record<string, number>) {
  if (!state.readyToAnalyze) return current;
  const next = { ...current };
  for (const criterion of state.criteria) {
    if (state.options.length === 2) {
      const leftKey = `${criterion.id}:${state.options[0].id}`;
      const rightKey = `${criterion.id}:${state.options[1].id}`;
      if (next[leftKey] === undefined && next[rightKey] === undefined) { next[leftKey] = 60; next[rightKey] = 40; }
      continue;
    }
    for (const option of state.options) {
      const key = `${criterion.id}:${option.id}`;
      if (next[key] === undefined) next[key] = 50;
    }
  }
  return next;
}

export function DecisionWorkspace() {
  const [stage, setStage] = useState<Stage>(1);
  const [decision, setDecision] = useState("");
  const [submittedDecision, setSubmittedDecision] = useState("");
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [decisionState, setDecisionState] = useState<ApiDecisionState | null>(null);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState("");
  const [conversationLoading, setConversationLoading] = useState(false);
  const [conversationError, setConversationError] = useState<string | null>(null);
  const [responseMode, setResponseMode] = useState<"AI" | "GUIDED" | "FALLBACK">("GUIDED");
  const conversationEpoch = useRef(0);
  const [suggestionMode, setSuggestionMode] = useState<"SINGLE" | "ORDERED">("SINGLE");
  const [suggestedAnswers, setSuggestedAnswers] = useState<SuggestedAnswer[]>([]);
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisStep, setAnalysisStep] = useState(0);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [assessmentScores, setAssessmentScores] = useState<Record<string, number>>({});
  const [decisionResult, setDecisionResult] = useState<DecisionResult | null>(null);
  const [apiStatus, setApiStatus] = useState<ApiStatus>("checking");
  const [resultTab, setResultTab] = useState<ResultTab>("map");

  useEffect(() => {
    const controller = new AbortController();
    getHealth(controller.signal).then(() => setApiStatus("online")).catch((error: unknown) => {
      if (error instanceof DOMException && error.name === "AbortError") return;
      setApiStatus("offline");
    });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (stage !== 3 || !analysisLoading) return;
    const timer = window.setInterval(() => setAnalysisStep((current) => Math.min(current + 1, analysisItems.length - 1)), 700);
    return () => window.clearInterval(timer);
  }, [stage, analysisLoading]);

  async function submitDecision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = decision.trim();
    if (!message || conversationLoading) return;
    setSubmittedDecision(message); setConversationLoading(true); setConversationError(null);
    const epoch = ++conversationEpoch.current;
    try {
      const response = await withResponsePacing(() => createDecision(message));
      if (epoch !== conversationEpoch.current) return;
      setResponseMode(response.responseMode ?? "AI");
      setSessionId(response.sessionId); setDecisionState(response.state); setSuggestionMode(response.suggestionMode ?? "SINGLE"); setSuggestedAnswers(response.suggestedAnswers ?? []);
      setAssessmentScores((current) => ensureAssessmentScores(response.state, current));
      setChatMessages([
        { id: messageId("user"), role: "user", content: message, status: "sent" },
        { id: messageId("assistant"), role: "assistant", content: response.assistantMessage },
      ]);
      setDecision(""); setStage(2);
    } catch (error) { if (epoch === conversationEpoch.current) setConversationError(toConversationError(error)); }
    finally { if (epoch === conversationEpoch.current) setConversationLoading(false); }
  }

  async function continueConversation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = chatInput.trim();
    if (!message || !sessionId || conversationLoading) return;
    const id = messageId("user");
    setChatMessages((current) => [...current.filter((item) => item.status !== "failed"), { id, role: "user", content: message, status: "sending" }]);
    setChatInput(""); setConversationLoading(true); setConversationError(null); setAnalysisError(null);
    const epoch = ++conversationEpoch.current;
    try {
      const response = await withResponsePacing(() => sendDecisionMessage(sessionId, message));
      if (epoch !== conversationEpoch.current) return;
      setResponseMode(response.responseMode ?? "AI");
      setDecisionState(response.state); setSuggestionMode(response.suggestionMode ?? "SINGLE"); setSuggestedAnswers(response.suggestedAnswers ?? []);
      setAssessmentScores((current) => ensureAssessmentScores(response.state, current));
      setChatMessages((current) => [...current.map((item) => item.id === id ? { ...item, status: "sent" as const } : item), { id: messageId("assistant"), role: "assistant", content: response.assistantMessage }]);
    } catch (error) {
      if (epoch !== conversationEpoch.current) return;
      setChatMessages((current) => current.map((item) => item.id === id ? { ...item, status: "failed" as const } : item));
      setChatInput(message); setConversationError(toConversationError(error));
    } finally { if (epoch === conversationEpoch.current) setConversationLoading(false); }
  }

  async function runAnalysis() {
    if (!sessionId || !decisionState?.readyToAnalyze || analysisLoading) return;
    const assessments: CriterionOptionAssessment[] = decisionState.criteria.flatMap((criterion) => {
      const values = decisionState.options.map((option) => assessmentScores[`${criterion.id}:${option.id}`] ?? 50);
      const comparison = decisionState.options.length === 2
        ? `${criterion.name} 기준에서 ${decisionState.options[0].name} ${values[0]}점, ${decisionState.options[1].name} ${values[1]}점으로 사용자가 한 번에 상대 평가함`
        : `${criterion.name} 기준에서 사용자가 선택지별 적합도를 직접 평가함`;
      return decisionState.options.map((option, index) => ({
        criterionId: criterion.id,
        optionId: option.id,
        score: values[index],
        reason: comparison,
        sourceType: "USER_ASSUMPTION" as const,
      }));
    });
    const epoch = ++conversationEpoch.current;
    setAnalysisLoading(true); setAnalysisError(null); setAnalysisStep(0); setStage(3);
    try {
      const response = await analyzeDecision(sessionId, assessments);
      if (epoch !== conversationEpoch.current) return;
      const enriched = await enrichDecisionResult(sessionId).catch(() => ({
        ...response, result: { ...response.result, narrativeStatus: "FALLBACK" as const, narrativeError: "NETWORK_ERROR" },
      }));
      if (epoch !== conversationEpoch.current) return;
      setDecisionResult(enriched.result); setAnalysisStep(analysisItems.length); setStage(4);
    } catch (error) {
      if (epoch === conversationEpoch.current) { setAnalysisError(toConversationError(error)); setStage(2); }
    } finally { if (epoch === conversationEpoch.current) setAnalysisLoading(false); }
  }

  async function retryNarrative() {
    if (!sessionId || analysisLoading) return;
    const epoch = conversationEpoch.current;
    setAnalysisLoading(true);
    try {
      const response = await enrichDecisionResult(sessionId);
      if (epoch === conversationEpoch.current) setDecisionResult(response.result);
    } catch {
      if (epoch === conversationEpoch.current) setDecisionResult((current) => current ? { ...current, narrativeStatus: "FALLBACK", narrativeError: "NETWORK_ERROR" } : current);
    } finally { if (epoch === conversationEpoch.current) setAnalysisLoading(false); }
  }

  function resetDemo() {
    conversationEpoch.current += 1;
    setResponseMode("GUIDED");
    setStage(1); setDecision(""); setSubmittedDecision(""); setSessionId(null); setDecisionState(null); setChatMessages([]); setChatInput(""); setConversationLoading(false); setConversationError(null); setSuggestionMode("SINGLE"); setSuggestedAnswers([]); setAnalysisLoading(false); setAnalysisError(null); setAnalysisStep(0); setAssessmentScores({}); setDecisionResult(null); setResultTab("map");
  }

  return (
    <main className="app-shell">
      <AppSidebar apiStatus={apiStatus} onReset={resetDemo} />
      <section className="workspace" id="workspace">
        <header className="stepper" aria-label="결정 분석 진행 단계">
          {steps.map((item, index) => {
            const canOpen = item.number < stage || item.number === stage || (item.number === 2 && Boolean(sessionId)) || (item.number === 4 && Boolean(decisionResult));
            return <button className={`step ${stage === item.number ? "active" : ""} ${stage > item.number ? "complete" : ""}`} key={item.number} type="button" disabled={!canOpen} onClick={() => canOpen && setStage(item.number)} aria-current={stage === item.number ? "step" : undefined}><span className="step-number">{stage > item.number ? <Check size={15} /> : item.number}</span><div><strong>{item.title}</strong><small>{item.subtitle}</small></div>{index < steps.length - 1 && <ArrowRight className="step-arrow" size={18} />}</button>;
          })}
        </header>

        {stage === 1 && <QuestionStage decision={decision} setDecision={setDecision} submittedDecision={submittedDecision} state={decisionState} loading={conversationLoading} error={conversationError} onSubmit={submitDecision} />}
        {stage === 2 && <><div className="response-mode-notice" role="status">{responseMode === "FALLBACK" ? "AI 연결이 원활하지 않아 기본 안내로 진행 중입니다. 입력은 저장되며, 맞춤 AI 분석에는 서버 API 설정 확인이 필요합니다." : responseMode === "GUIDED" ? "기본 안내로 선택지를 정리하고 있어요." : "AI가 답변 내용을 반영하고 있어요."}</div><CollectionStage decision={submittedDecision} messages={chatMessages} state={decisionState} suggestionMode={suggestionMode} suggestedAnswers={suggestedAnswers} input={chatInput} setInput={setChatInput} loading={conversationLoading} error={analysisError ?? conversationError} onSubmit={continueConversation} scores={assessmentScores} onScoreChange={(key, value) => setAssessmentScores((current) => ({ ...current, [key]: value }))} onAnalyze={runAnalysis} /></>}
        {stage === 3 && <AnalysisStage progress={analysisStep} loading={analysisLoading} onShowResult={() => decisionResult && setStage(4)} />}
        {stage === 4 && decisionResult && <ResultStage activeTab={resultTab} setActiveTab={setResultTab} result={decisionResult} retrying={analysisLoading} onRetry={retryNarrative} />}

        <footer className="workspace-footer"><span>망설임을 근거 있는 다음 행동으로 바꿔드립니다.</span><span>사용자 입력 · AI 추론 · 사용자 가정을 분리해 표시합니다.</span></footer>
      </section>
    </main>
  );
}

function AppSidebar({ apiStatus, onReset }: { apiStatus: ApiStatus; onReset: () => void }) {
  return <aside className="app-sidebar"><a className="app-brand" href="#workspace"><span className="logo-mark"><CircleDot size={18} /><Sparkles size={13} /></span><span>AI Decision Map</span></a><div className="sidebar-intro"><p className="sidebar-kicker">AI DECISION FACILITATOR</p><h1>대화로 시작하고,<br /><strong>지도로 이해하고,</strong><br />현명하게 결정하세요.</h1><p>복잡한 고민을 대화로 풀어내고, 선택지와 판단 기준을 명확한 구조로 보여드립니다.</p></div><div className="feature-list"><SidebarFeature icon={<MessageCircleMore size={19} />} color="purple" title="대화형 질문" copy="한 번에 한 가지씩 꼭 필요한 질문만 드려요." /><SidebarFeature icon={<Map size={19} />} color="green" title="결정 지도" copy="선택지와 판단 기준의 영향을 시각화해요." /><SidebarFeature icon={<Lightbulb size={19} />} color="amber" title="AI 인사이트" copy="점수의 이유와 놓친 위험을 함께 알려드려요." /></div><button className="reset-demo" type="button" onClick={onReset}><RotateCcw size={15} /> 새 결정 시작</button><div className={`service-state ${apiStatus}`} aria-live="polite"><span />{apiStatus === "checking" ? "서비스 연결 확인 중" : apiStatus === "online" ? "AI Decision Map 연결됨" : "백엔드 연결 대기 중"}</div></aside>;
}

function SidebarFeature({ icon, color, title, copy }: { icon: React.ReactNode; color: string; title: string; copy: string }) {
  return <div className="feature-item"><span className={`feature-icon ${color}`}>{icon}</span><div><strong>{title}</strong><p>{copy}</p></div></div>;
}

function QuestionStage({ decision, setDecision, submittedDecision, state, loading, error, onSubmit }: { decision: string; setDecision: (value: string) => void; submittedDecision: string; state: ApiDecisionState | null; loading: boolean; error: string | null; onSubmit: (event: FormEvent<HTMLFormElement>) => void }) {
  return <div className="workspace-body"><section className="conversation-card"><AssistantHeader label="1단계 · 질문하기" /><div className="conversation-flow" aria-live="polite"><div className="assistant-greeting"><span className="mini-avatar"><Bot size={18} /></span><div className="message-content"><strong>안녕하세요! 👋<br />어떤 결정을 도와드릴까요?</strong><p>고민을 자연스럽게 말씀해주세요.<br />AI가 선택지와 중요한 기준을 함께 정리해드릴게요.</p></div></div>{submittedDecision && <UserMessage message={submittedDecision} status="sent" />}<div className="prompt-label">이런 고민으로 시작할 수 있어요</div><div className="prompt-grid">{scenarios.slice(0, 4).map((scenario) => <button key={scenario.category} type="button" onClick={() => setDecision(scenario.prompt)}><span>{scenario.category}</span>{scenario.prompt}<ArrowRight size={15} /></button>)}</div></div>{error && <ConversationError message={error} />}<MessageComposer decision={decision} setDecision={setDecision} onSubmit={onSubmit} loading={loading} /></section><DecisionSnapshot hasDecision={Boolean(submittedDecision)} state={state} /></div>;
}

function AssistantHeader({ label }: { label: string }) {
  return <header className="card-header"><span className="assistant-avatar"><Bot size={19} /></span><div><strong>AI 어시스턴트</strong><small><i /> 온라인</small></div><span className="stage-badge">{label}</span></header>;
}

function UserMessage({ message, status = "sent" }: { message: string; status?: ChatMessage["status"] }) {
  const statusText = status === "sending" ? "전송 중" : status === "failed" ? "전송 실패 · 다시 시도해주세요" : "전달 완료";
  return <div className={`user-message ${status ?? "sent"}`}><div><p>{message}</p><small>{statusText}</small></div><span><UserRound size={17} /></span></div>;
}

function MessageComposer({ decision, setDecision, onSubmit, loading = false, id = "decision-message" }: { decision: string; setDecision: (value: string) => void; onSubmit: (event: FormEvent<HTMLFormElement>) => void; loading?: boolean; id?: string }) {
  function handleKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (event.nativeEvent.isComposing || event.key !== "Enter" || event.shiftKey) return;
    if (decision.endsWith("\n")) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); }
  }
  return <form className="composer" onSubmit={onSubmit}><label className="sr-only" htmlFor={id}>결정 고민 입력</label><textarea id={id} value={decision} onChange={(event) => setDecision(event.target.value)} onKeyDown={handleKeyDown} placeholder={loading ? "AI가 답변을 정리하고 있어요..." : "메시지를 입력하세요..."} rows={3} maxLength={2000} disabled={loading} /><div className="composer-footer"><span>{loading ? "답변을 구조화하고 있어요" : `Enter 두 번 눌러 전송 · ${decision.length}/2000`}</span><button type="submit" disabled={!decision.trim() || loading} aria-label="메시지 보내기">{loading ? <Sparkles size={18} /> : <Send size={18} />}</button></div></form>;
}

function ConversationError({ message }: { message: string }) {
  return <div className="conversation-error" role="alert"><CircleDot size={16} /><span>{message}</span></div>;
}

function DecisionSnapshot({ hasDecision, state }: { hasDecision: boolean; state: ApiDecisionState | null }) {
  const progress = state?.progress ?? (hasDecision ? 20 : 0);
  return <aside className="decision-snapshot" aria-label="현재 결정 상태"><header><div><span>DECISION STATE</span><h2>현재 결정 상태</h2></div><span className="live-pill">LIVE</span></header><div className="progress-block"><div><span>정보 수집 진행률</span><strong>{progress}%</strong></div><div className="progress-track"><i style={{ width: `${Math.max(progress, 4)}%` }} /></div></div><div className="state-empty"><span><Compass size={27} /></span><h3>{state?.decisionTitle ?? (hasDecision ? "결정 주제를 확인했어요" : "아직 수집된 정보가 없어요")}</h3><p>{state?.summary ?? (hasDecision ? "선택지와 판단 기준을 구체화하고 있어요." : "첫 메시지를 보내면 AI가 결정의 핵심을 하나씩 구조화합니다.")}</p></div><ul className="state-checklist"><li className={state || hasDecision ? "done" : "current"}><span>{state || hasDecision ? <Check size={14} /> : "1"}</span>결정 주제 파악</li><li className={state?.options.length ? "done" : ""}><span>{state?.options.length ? <Check size={14} /> : "2"}</span>선택지 확인</li><li className={state?.criteria.length ? "done" : ""}><span>{state?.criteria.length ? <Check size={14} /> : "3"}</span>중요 기준 수집</li><li className={state?.readyToAnalyze ? "done" : ""}><span>{state?.readyToAnalyze ? <Check size={14} /> : "4"}</span>분석 준비 완료</li></ul></aside>;
}

function CollectionStage({ decision, messages, state, suggestionMode, suggestedAnswers, input, setInput, loading, error, onSubmit, scores, onScoreChange, onAnalyze }: { decision: string; messages: ChatMessage[]; state: ApiDecisionState | null; suggestionMode: "SINGLE" | "ORDERED"; suggestedAnswers: SuggestedAnswer[]; input: string; setInput: (value: string) => void; loading: boolean; error: string | null; onSubmit: (event: FormEvent<HTMLFormElement>) => void; scores: Record<string, number>; onScoreChange: (key: string, value: number) => void; onAnalyze: () => void }) {
  const progress = state?.progress ?? 20;
  const complete = state?.readyToAnalyze ?? false;
  const endRef = useRef<HTMLDivElement>(null);
  const latestQuestion = [...messages].reverse().find((message) => message.role === "assistant")?.content ?? state?.nextQuestion ?? "";
  useEffect(() => {
    const container = endRef.current?.parentElement;
    container?.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
  }, [messages, loading]);
  return <div className="workspace-body collection-layout"><section className="conversation-card collection-card"><AssistantHeader label="2단계 · 정보 수집" /><div className="collection-progress"><div><span>{complete ? "분석 준비 완료" : `핵심 질문 ${state?.askedQuestions?.length ?? 1}/최대 12`}</span><strong>{progress}%</strong></div><div className="segmented-progress">{Array.from({ length: 6 }).map((_, index) => <i key={index} className={index < Math.ceil(progress / 17) ? "filled" : ""} />)}</div></div><div className="collection-messages" aria-live="polite">{messages.length ? messages.map((message) => message.role === "user" ? <UserMessage key={message.id} message={message.content} status={message.status} /> : <div className="assistant-question" key={message.id}><span><Bot size={16} /></span><p>{message.content}</p></div>) : decision && <UserMessage message={decision} status="sent" />}{loading && <div className="assistant-thinking"><span><Sparkles size={17} /></span><div><strong>답변을 잘 받았어요</strong><p>이미 확인한 내용은 건너뛰고 다음 핵심을 정리하고 있어요.</p><i><b /><b /><b /></i></div></div>}{complete && <div className="ready-message"><CheckCircle2 size={20} /><div><strong>질문은 여기까지면 충분해요.</strong><p>각 기준은 양쪽 선택지를 한 번만 비교하면 됩니다.</p></div></div>}<div ref={endRef} /></div>{error && <ConversationError message={error} />}<div className="answer-panel chat-answer-panel">{!complete ? <>{!loading && <SuggestionBadges key={latestQuestion} question={latestQuestion} state={state} mode={suggestionMode} suggestions={suggestedAnswers} setInput={setInput} />}<MessageComposer id="collection-message" decision={input} setDecision={setInput} onSubmit={onSubmit} loading={loading} /></> : <AssessmentPanel state={state} scores={scores} onScoreChange={onScoreChange} onAnalyze={onAnalyze} />}</div></section><CollectedState state={state} /></div>;
}

function SuggestionBadges({ question, state, mode, suggestions, setInput }: { question: string; state: ApiDecisionState | null; mode: "SINGLE" | "ORDERED"; suggestions: SuggestedAnswer[]; setInput: (value: string) => void }) {
  const fallback = getSuggestionConfig(question, state);
  const config: SuggestionConfig = suggestions.length === 4 ? { mode: mode === "ORDERED" ? "priority" : "single", label: mode === "ORDERED" ? "중요한 순서대로 눌러주세요" : "지금 생각과 가까운 답을 골라보세요", options: suggestions } : fallback;
  const [order, setOrder] = useState<string[]>([]);
  if (state && state.options.length < 2) return <div className="suggestion-box"><strong>비교할 실제 선택지를 직접 입력해주세요</strong><p>입력 예: 선택지: 짜장면 / 짬뽕<br />제품·여행지·진로 등 어떤 후보든 가능하며, 2~8개를 / 로 구분해주세요.</p></div>;
  if (state && !state.nextQuestion.trim()) return null;
  function select(option: SuggestedAnswer) {
    if (option.value === "__custom__") { setInput(""); window.requestAnimationFrame(() => document.getElementById("collection-message")?.focus({ preventScroll: true })); return; }
    const naturalValue = state?.options.find((item) => item.id === option.value)?.name ?? option.value;
    if (config.mode === "single") { setInput(naturalValue); window.requestAnimationFrame(() => document.getElementById("collection-message")?.focus({ preventScroll: true })); return; }
    const next = order.includes(naturalValue) ? order.filter((item) => item !== naturalValue) : [...order, naturalValue];
    setOrder(next); setInput(next.length ? `우선순위는 ${next.map((item, index) => `${index + 1}순위 ${item}`).join(", ")}입니다.` : "");
  }
  return <div className="suggestion-box"><strong>{config.label}</strong><div>{config.options.map((option) => { const naturalValue = state?.options.find((item) => item.id === option.value)?.name ?? option.value; const naturalLabel = state?.options.find((item) => item.id === option.label)?.name ?? option.label; const selectedIndex = order.indexOf(naturalValue); return <button className={selectedIndex >= 0 ? "selected" : ""} key={`${option.label}-${option.value}`} type="button" onClick={() => select(option)}>{selectedIndex >= 0 && <b>{selectedIndex + 1}</b>}{naturalLabel}</button>; })}</div></div>;
}

function AssessmentPanel({ state, scores, onScoreChange, onAnalyze }: { state: ApiDecisionState | null; scores: Record<string, number>; onScoreChange: (key: string, value: number) => void; onAnalyze: () => void }) {
  if (!state) return null;
  const pairwise = state.options.length === 2;
  return <section className="assessment-panel"><header><div><span>QUICK COMPARISON</span><h3>{pairwise ? "기준마다 더 가까운 선택지 쪽으로 한 번만 움직여주세요" : "선택지별 예상 점수를 확인해주세요"}</h3><p>{pairwise ? "가운데에 가까울수록 차이가 작고, 끝에 가까울수록 한쪽이 더 적합하다는 뜻입니다." : "0점은 매우 불리, 100점은 매우 유리입니다."}</p></div></header><div className="assessment-list">{state.criteria.map((criterion) => { if (pairwise) { const left = state.options[0]; const right = state.options[1]; const rightValue = scores[`${criterion.id}:${right.id}`] ?? 40; const preference = rightValue <= 20 ? `${left.name}이 훨씬 유리` : rightValue === 40 ? `${left.name}이 조금 유리` : rightValue === 60 ? `${right.name}이 조금 유리` : `${right.name}이 더 유리`; return <article className="pairwise-assessment" key={criterion.id}><div className="assessment-criterion"><strong>{criterion.name}</strong><span>중요도 {Math.round(criterion.weight * 100)}%</span></div><div className="pairwise-labels"><strong>{left.name}</strong><em>{preference}</em><strong>{right.name}</strong></div><input aria-label={`${criterion.name}에서 ${left.name}와 ${right.name} 비교`} type="range" min="0" max="100" step="20" value={rightValue} onChange={(event) => { const value = Number(event.target.value); onScoreChange(`${criterion.id}:${left.id}`, 100 - value); onScoreChange(`${criterion.id}:${right.id}`, value); }} /><div className="pairwise-ticks">{[0,20,40,60,80,100].map((tick) => <span key={tick}>{tick}</span>)}</div></article>; } return <article key={criterion.id}><div className="assessment-criterion"><strong>{criterion.name}</strong><span>가중치 {Math.round(criterion.weight * 100)}%</span></div><div className="assessment-options">{state.options.map((option) => { const key = `${criterion.id}:${option.id}`; const value = scores[key] ?? 50; return <label key={option.id}><span>{option.name}<strong>{value}점</strong></span><input type="range" min="0" max="100" step="10" value={value} onChange={(event) => onScoreChange(key, Number(event.target.value))} /></label>; })}</div></article>; })}</div><button className="primary-action wide" type="button" onClick={onAnalyze}>장단점과 대안 분석하기 <ArrowRight size={17} /></button></section>;
}

function CollectedState({ state }: { state: ApiDecisionState | null }) {
  const leaning = state?.options.find((option) => option.id === state.userLeaningOptionId)?.name;
  return <aside className="collected-state"><header><span>DECISION STATE</span><h2>{state?.decisionTitle ?? "수집된 결정 정보"}</h2><p>{state?.summary ?? "대화에 따라 실시간으로 업데이트됩니다."}</p></header>{leaning && <section className="leaning-card"><small>대화에서 보인 마음의 방향</small><strong>{leaning}</strong><p>{state?.userLeaningEvidence[0] ?? "사용자의 표현을 바탕으로 감지했어요."}</p></section>}<StateGroup title="확인된 선택지" items={state?.options.map((option) => option.name) ?? []} tone="blue" /><StateGroup title="중요 판단 기준" items={state?.criteria.map((criterion) => `${criterion.name} · ${Math.round(criterion.weight * 100)}%`) ?? []} tone="green" /><StateGroup title="확인된 사실" items={state?.knownFacts ?? []} tone="purple" /><StateGroup title="사용자 가정" items={state?.assumptions ?? []} tone="amber" /><StateGroup title="AI 추론" items={state?.aiInferences ?? []} tone="gray" /><div className="source-legend"><span><i className="user-source" />사용자 입력 사실</span><span><i className="assumption-source" />사용자 가정</span><span><i className="ai-source" />AI 추론</span></div></aside>;
}

function StateGroup({ title, items, tone }: { title: string; items: string[]; tone: string }) {
  if (!items.length) return null;
  return <section className="state-group"><h3>{title}</h3><div>{items.map((item, index) => <span className={tone} key={`${item}-${index}`}>{item}</span>)}</div></section>;
}

function AnalysisStage({ progress, loading, onShowResult }: { progress: number; loading: boolean; onShowResult: () => void }) {
  const complete = !loading && progress >= analysisItems.length;
  return <div className="analysis-layout"><section className="analysis-card" aria-live="polite"><div className="analysis-visual" aria-hidden="true">{Array.from({ length: 7 }).map((_, index) => <i key={index} />)}</div><span className="analysis-kicker">DECISION ENGINE</span><h2>{complete ? "분석이 완료됐어요" : "실제 API 결과를 계산하고 있어요"}</h2><p>{complete ? "선택지별 점수와 근거를 결정 지도로 정리했습니다." : "입력한 평가를 정규화하고 결정 지도를 먼저 만드는 중입니다."}</p><div className="analysis-list">{analysisItems.map((item, index) => <div className={index < progress ? "done" : index === progress ? "current" : "pending"} key={item}><span>{index < progress ? <Check size={14} /> : index === progress ? <Sparkles size={14} /> : <Circle size={11} />}</span><strong>{item}</strong>{index < progress && <small>완료</small>}{index === progress && !complete && <small>진행 중</small>}</div>)}</div>{complete ? <button className="primary-action wide" type="button" onClick={onShowResult}>결정 지도 확인하기 <ArrowRight size={17} /></button> : <small className="analysis-time">점수 결과를 먼저 보여드리고 AI 설명은 이어서 보강합니다.</small>}</section></div>;
}

export function ResultStage({ activeTab, setActiveTab, result, retrying = false, onRetry }: { activeTab: ResultTab; setActiveTab: (tab: ResultTab) => void; result: DecisionResult; retrying?: boolean; onRetry?: () => void }) {
  const qualityLabel = { HIGH: "높음", MEDIUM: "보통", LOW: "추가 확인 필요" }[result.evidenceQuality.level];
  const ready = result.narrativeStatus === "READY";
  return <div className="result-layout">
    <section className="result-dashboard">
      <div className="result-tabs" role="tablist" aria-label="결정 분석 결과">{resultTabs.map((tab) => <button role="tab" aria-selected={activeTab === tab.id} className={activeTab === tab.id ? "active" : ""} key={tab.id} type="button" onClick={() => setActiveTab(tab.id)}>{tab.label}</button>)}</div>
      {!ready && <div className="narrative-notice" role="status"><strong>{retrying ? "AI가 대안과 근거를 다시 검토하고 있어요." : "맞춤 AI 분석이 아직 완료되지 않았어요."}</strong><p>아래 내용은 기본 점검 안내입니다. 선호 점수만으로 추천을 확정하지 않습니다.</p>{result.narrativeError === "LLM_NOT_CONFIGURED" && <p>서버의 실제 LLM_API_KEY 설정이 필요합니다.</p>}{result.narrativeError === "LLM_EVIDENCE_INVALID" && <p>AI 답변의 근거를 검증하지 못해 표시하지 않았습니다.</p>}{onRetry && <button type="button" onClick={onRetry} disabled={retrying}>{retrying ? "분석 중…" : "맞춤 AI 분석 다시 시도"}</button>}</div>}
      <div className="recommendation-strip advice-first"><div><span>{ready ? "당신의 상황을 바탕으로 한 제안" : "결정 전 기본 점검"}</span><h2>{result.guidance.headline}</h2><p>{result.guidance.rationale}</p><strong className="encouragement-copy">{result.guidance.encouragement}</strong>
        {!!result.guidance.evidenceRefs?.length && <div className="advice-evidence"><b>이렇게 판단한 대화 근거</b>{result.guidance.evidenceRefs.map((ref) => <p key={ref}>{ref}</p>)}<small>사용자가 제공한 정보이며 외부 검증된 사실과는 다릅니다.</small></div>}
      </div></div>
      <div className="practical-advice">{result.guidance.nextAction && <article><span>지금 할 한 가지</span><h3>{result.guidance.nextAction}</h3></article>}{result.guidance.practicalAlternative && <article><span>부담을 줄이는 대안</span><h3>{result.guidance.practicalAlternative}</h3></article>}</div>
      {activeTab === "map" && <DecisionMapPanel result={result} />}
      {activeTab === "compare" && <ComparisonPanel result={result} />}
      {activeTab === "insight" && <InsightPanel result={result} />}
      {activeTab === "action" && <ActionPanel result={result} />}
    </section>
    <aside className="result-side"><section className="scenario-card-panel"><header><Lightbulb size={19} /><div><h3>해석과 근거</h3><p>점수는 선호 경향이지 성공 확률이 아닙니다.</p></div></header><div className="result-metric"><span>대화 근거 품질</span><strong>{qualityLabel}</strong></div><div className="evidence-counts"><span>사용자 진술 {result.evidenceQuality.factCount}</span><span>가정 {result.evidenceQuality.assumptionCount}</span><span>AI 추론 {result.evidenceQuality.inferenceCount}</span></div><p>필수 입력을 채웠다는 것과 실제 조건이 확인되었다는 것은 다릅니다. 비용·조건·가능 여부는 결정 전에 확인하세요.</p></section><section className="share-panel"><h3>공유 및 저장</h3><p>분석 결과 활용 기능은 다음 단계에서 연결할 수 있어요.</p><div><button type="button"><Save size={16} />결과 저장</button><button type="button"><Share2 size={16} />링크 공유</button><button type="button"><Download size={16} />PDF</button></div></section><section className="source-panel"><h3>응원하는 방식</h3><p>마음이 가는 방향의 기대 효과를 설명하되, 중요한 위험이나 부족한 근거를 숨기지 않습니다.</p></section></aside>
  </div>;
}

function DecisionMapPanel({ result }: { result: DecisionResult }) {
  return <div className="map-panel"><DecisionMapGraph result={result} /><div className="impact-legend"><span><i className="positive" />70점 이상</span><span><i className="neutral" />45~69점</span><span><i className="negative" />44점 이하</span><span><i className="recommended" />응원 방향</span></div><section className="map-alternative-summary"><header><span>대안별 핵심 해석</span><h3>지도에서 보이는 차이를 실제 선택 언어로 정리했어요</h3></header><div>{result.optionProfiles.map((profile) => { const option = result.options.find((item) => item.id === profile.optionId); const encouraged = profile.optionId === result.guidance.encouragedOptionId; return <article className={encouraged ? "encouraged" : ""} key={profile.optionId}><div><strong>{option?.name}</strong>{encouraged && <em>현재 응원 방향</em>}</div><p><b>선택할 이유</b>{profile.pros.slice(0,2).join(" · ")}</p><p><b>주의할 점</b>{profile.cons.slice(0,2).join(" · ")}</p></article>; })}</div></section></div>;
}

function ComparisonPanel({ result }: { result: DecisionResult }) {
  const findAssessment = (criterionId: string, optionId: string) => result.assessments.find((item) => item.criterionId === criterionId && item.optionId === optionId);
  return <div className="comparison-panel"><header><div><span>선택지 비교 테이블</span><h3>어떤 상황에 적합한지, 얻는 것과 감수할 것을 비교하세요.</h3></div></header><div className="option-profile-grid">{result.optionProfiles.map((profile) => { const option = result.options.find((item) => item.id === profile.optionId); return <article key={profile.optionId} className={profile.optionId === result.guidance.encouragedOptionId ? "encouraged" : ""}><header><strong>{option?.name}</strong>{profile.optionId === result.guidance.encouragedOptionId && <span>응원 방향</span>}</header><div><h4>이런 경우에 적합해요</h4><p>{profile.bestWhen}</p></div><div><h4>기대할 장점</h4>{profile.pros.map((item) => <p key={item}>+ {item}</p>)}</div><div><h4>확인할 부담</h4>{profile.cons.map((item) => <p key={item}>− {item}</p>)}</div></article>; })}</div><details className="score-details"><summary>참고 자료: 입력한 선호 점수 비교</summary><p>높을수록 사용자가 더 선호한다는 뜻입니다. 실제 비용·품질·성공 가능성을 측정한 수치는 아닙니다.</p><div className="table-wrap" tabIndex={0} role="region" aria-label="선호 점수 비교표 — 가로로 스크롤"><table><thead><tr><th>판단 기준</th>{result.options.map((option) => <th key={option.id}>{option.name}</th>)}<th>최대 차이</th></tr></thead><tbody>{result.criteria.map((criterion) => { const values = result.options.map((option) => findAssessment(criterion.id, option.id)?.score ?? 0); const delta = Math.max(...values) - Math.min(...values); return <tr key={criterion.id}><th>{criterion.name}<small>가중치 {Math.round(criterion.normalizedWeight * 100)}%</small></th>{result.options.map((option) => { const assessment = findAssessment(criterion.id, option.id); return <td key={option.id}><strong>{assessment?.score ?? "-"}점</strong><small>사용자 상대 평가</small></td>; })}<td><strong className="delta-score">{delta}점</strong><small>{delta >= 20 ? "결정에 큰 영향" : "차이가 작음"}</small></td></tr>; })}</tbody><tfoot><tr><th>종합 점수</th>{result.options.map((option) => <td key={option.id}><strong>{option.score ?? "-"}점</strong><small>{option.rank ? `${option.rank}순위` : "순위 없음"}</small></td>)}<td /></tr></tfoot></table></div>{result.scenarioForecasts.length > 0 && <ScenarioChart result={result} />}</details></div>;
}

function ScenarioChart({ result }: { result: DecisionResult }) {
  const [selected, setSelected] = useState(result.scenarioForecasts[0]?.criterionId ?? "");
  const forecast = result.scenarioForecasts.find((item) => item.criterionId === selected) ?? result.scenarioForecasts[0];
  if (!forecast) return null;
  const colors = ["#6046df", "#19a974", "#e45f78", "#3d7de2"];
  const x = (weight: number) => 52 + (weight / 60) * 620;
  const y = (score: number) => 220 - (score / 100) * 180;
  return <section className="scenario-forecast"><header><div><span>선호 민감도</span><h3>중요도를 바꾸면 선호 순위가 어떻게 달라질까요?</h3></div><select value={forecast.criterionId} onChange={(event) => setSelected(event.target.value)} aria-label="시나리오 기준 선택">{result.scenarioForecasts.map((item) => <option key={item.criterionId} value={item.criterionId}>{item.criterionName}</option>)}</select></header><div className="scenario-svg-wrap"><svg viewBox="0 0 720 260" role="img" aria-label={`${forecast.criterionName} 가중치 변화에 따른 선택지 점수 그래프`}><g className="chart-grid">{[20, 40, 60, 80, 100].map((score) => <g key={score}><line x1="52" x2="682" y1={y(score)} y2={y(score)} /><text x="12" y={y(score) + 4}>{score}</text></g>)}</g>{result.options.map((option, index) => { const points = forecast.points.map((point) => ({ x: x(point.weightPercent), y: y(point.optionScores[option.id] ?? 0), score: point.optionScores[option.id] ?? 0, weight: point.weightPercent })); return <g key={option.id} style={{ color: colors[index % colors.length] }}><polyline points={points.map((point) => `${point.x},${point.y}`).join(" ")} /><g>{points.map((point) => <circle key={point.weight} cx={point.x} cy={point.y} r={point.weight === forecast.currentWeightPercent ? 6 : 4}><title>{`${option.name}: 가중치 ${point.weight}%, ${point.score}점`}</title></circle>)}</g></g>; })}<line className="current-weight-line" x1={x(forecast.currentWeightPercent)} x2={x(forecast.currentWeightPercent)} y1="28" y2="226" /><text className="current-weight-label" x={x(forecast.currentWeightPercent) + 6} y="24">현재 {forecast.currentWeightPercent}%</text>{forecast.points.map((point) => <text className="x-label" key={point.weightPercent} x={x(point.weightPercent)} y="246">{point.weightPercent}%</text>)}</svg></div><div className="chart-legend">{result.options.map((option, index) => <span key={option.id}><i style={{ background: colors[index % colors.length] }} />{option.name}</span>)}</div></section>;
}

const insightLabels = { KEY_DRIVER: "핵심 동인", TRADE_OFF: "트레이드오프", RISK: "주의할 위험", MISSING_INFORMATION: "추가 정보" };
function InsightPanel({ result }: { result: DecisionResult }) {
  if (!result.insights.length) return <div className="empty-result"><Lightbulb size={24} /><strong>생성된 인사이트가 없습니다.</strong><p>추가 정보를 입력한 뒤 다시 분석해보세요.</p></div>;
  return <div className="insight-grid">{result.insights.map((insight, index) => <article key={`${insight.type}-${index}`}><span>{index + 1}</span><div><small>{insightLabels[insight.type]} · 신뢰도 {{ HIGH: "높음", MEDIUM: "보통", LOW: "낮음" }[insight.confidence]}</small><h3>{insight.title}</h3><p>{insight.content}</p>{insight.evidenceRefs.length > 0 && <div className="insight-evidence"><strong>확인 근거</strong>{insight.evidenceRefs.map((item) => <em key={item}>{item}</em>)}</div>}{insight.assumptionRefs.length > 0 && <div className="insight-evidence assumptions"><strong>가정</strong>{insight.assumptionRefs.map((item) => <em key={item}>{item}</em>)}</div>}<p className="change-note"><b>달라질 수 있는 조건</b>{insight.whatCouldChange}</p></div></article>)}</div>;
}

function ActionPanel({ result }: { result: DecisionResult }) {
  const ruleLabels = { SELECT: "선택하기", REASSESS: "다시 비교", PAUSE: "잠시 보류" };
  return <div className="action-panel"><div className="action-timeline">{result.actionPlan.map((action) => <article key={action.order}><span>{action.order}</span><div><small>{action.timing}</small><h3>{action.task}</h3><p>{action.why}</p>{action.evidenceNeeded.length > 0 && <p className="evidence-needed"><b>준비할 근거</b>{action.evidenceNeeded.join(" · ")}</p>}<p className="done-when"><CheckCircle2 size={15} /><b>완료 기준</b>{action.doneWhen}</p></div></article>)}</div><div className="decision-rules"><h3>이 조건이면 다음으로 가세요</h3>{result.decisionRules.map((rule) => <article key={`${rule.outcome}-${rule.condition}`} className={rule.outcome.toLowerCase()}><strong>{ruleLabels[rule.outcome]}</strong><p>{rule.condition}</p></article>)}</div></div>;
}
