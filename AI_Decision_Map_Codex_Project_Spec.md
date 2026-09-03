# AI Decision Map - Codex Development Specification

## 0. Purpose

This document is the single source of truth for building **AI Decision Map**, an entry for **Wanted AI Championship 2026**.

The immediate goal is to get a working MVP deployed on the existing Linux server within this week. The final goal is not merely to finish the service, but to maximize the chance of advancing and placing in the competition.

Core product statement:

> **AI와 대화하면서 사용자의 고민을 구조화하고, 선택지와 판단 기준을 시각적인 Decision Map으로 보여주어 사용자가 스스로 더 명확한 결정을 내릴 수 있도록 돕는 서비스.**

Core UX:

> **Conversation -> Decision State -> Decision Engine -> Decision Map -> What-if -> AI Insight**

The AI must **not make the decision for the user**. It should help the user understand the structure of the decision, surface trade-offs, and show how the result changes when priorities change.

---

# 1. Product Goals

## 1.1 Competition goals

The MVP must demonstrate:

1. **기획력** - The product concept must be immediately understandable.
2. **실현 가능성** - A real public URL must work reliably during judging.
3. **확장성** - The service should support many kinds of decisions beyond one example.
4. **AI 활용 적절성** - AI must be central to conversation, extraction, and explanation.
5. **기술력** - The system should clearly combine LLM reasoning, deterministic scoring, structured data, and interactive visualization.
6. **발표력** - A judge should understand the value within a 30-60 second demo.

## 1.2 MVP success condition

A first-time user should be able to:

1. Open the service.
2. Enter a decision they are struggling with.
3. Talk with the AI for roughly 5-8 turns.
4. Let the AI identify options, criteria, missing information, and priorities.
5. Generate a Decision Map.
6. See scores and explanations for each option.
7. Change criterion weights using sliders.
8. See the outcome update immediately.
9. Understand that the AI is not deciding for them, but helping them think.

---

# 2. Representative User Scenarios

The service must **not** be branded as an "이직 상담 AI". Job change is only one example.

## 2.1 Primary demo scenario - Housing decision

Example user input:

> 아이 때문에 이사를 고민하고 있어. 지금 집을 팔고 다른 지역으로 갈지, 그냥 계속 살지 고민돼.

The AI should gather factors such as:

- 주거비
- 대출 또는 월 부담
- 통근 시간
- 아이 교육 및 통학
- 생활환경
- 현재 주거 만족도
- 장기 거주 계획
- 안정성

Expected map:

```text
                    우리 가족의 결정
                           ●
                         /   \
                        /     \
                    이사 ●     ● 유지
                       /|\     /|\
                      / | \   / | \
                   교육 주거 통근 비용 안정성
```

The user should be able to increase the importance of commute time or housing cost and see the result change.

## 2.2 Career decision

Examples:

- 지금 회사를 계속 다닐까, 이직할까?
- 개발자로 계속 갈까, PM으로 전환할까?
- 기존 기술을 더 깊게 공부할까, AI 개발을 공부할까?

Possible criteria:

- 연봉
- 성장 가능성
- 워라밸
- 안정성
- 커리어 영향
- 학습 비용

## 2.3 Travel decision

Example:

> 제주도와 일본 중 어디로 여행 갈까?

Possible criteria:

- 예산
- 여행 기간
- 동반자
- 음식
- 휴식
- 관광 경험
- 이동 편의성

## 2.4 Purchase decision

Example:

> 맥북을 살까, 지금 노트북을 계속 쓸까?

Possible criteria:

- 비용
- 성능
- 생산성
- 사용 빈도
- 현재 불편함
- 교체 시급성

## 2.5 Learning decision

Example:

> AI를 공부한다면 Python부터 할까, LLM부터 할까?

Possible criteria:

- 현재 실력
- 목표
- 학습 시간
- 난이도
- 취업/프로젝트 활용도

## 2.6 Business decision

Example:

> 다음 개발 기능으로 A와 B 중 무엇을 먼저 만들까?

Possible criteria:

- 개발 비용
- 예상 사용자 가치
- 개발 기간
- 매출 영향
- 기술 리스크
- 전략적 중요도

---

# 3. Core UX

## 3.1 Screen 1 - Landing

Main message:

> **결정하기 어려운 순간, AI와 이야기해보세요.**
>
> 고민을 이야기하면 AI가 질문하고, 정리하고, 선택지를 비교해드립니다.

Main input:

```text
무엇을 결정하려고 하나요?
예: 집을 살지 전세로 살지 고민돼
```

Example cards:

- 주거
- 여행
- 커리어
- 구매
- 학습
- 비즈니스

Clicking an example should prefill a starter prompt.

## 3.2 Screen 2 - Conversation

Layout recommendation:

### Left panel

- Current progress
- Decision title
- Known options
- Known criteria
- Missing information

Example:

```text
현재 진행률 60%

✓ 결정 주제 파악
✓ 선택지 파악
✓ 중요 기준 파악
○ 부족한 정보 확인 중
```

### Main panel

Chat conversation with the AI.

The AI should ask **one useful question at a time**, not a questionnaire dump.

## 3.3 Screen 3 - Analysis transition

After sufficient information has been gathered, display a button:

> **결정 지도 만들기**

Show analysis animation:

```text
✓ 대화 내용 분석
✓ 선택지 추출
✓ 판단 기준 추출
✓ 우선순위 구조화
✓ 선택지 점수 계산
✓ 인사이트 생성
```

## 3.4 Screen 4 - Decision Map

This is the most important screen in the product.

Recommended tabs:

- 결정 지도
- 비교 요약
- AI 인사이트
- What-if

Core layout:

```text
                 나의 결정
                    ●
                  /   \
                 /     \
            선택 A ●   ● 선택 B
                  /|\ /|\
               기준1 기준2 기준3
```

Recommended supporting cards:

- Overall option scores
- User priority weights
- Key pros and cons
- Facts / inference / assumptions
- Suggested next action

## 3.5 Screen 5 - What-if

Provide sliders for criterion weights.

Example:

```text
성장 가능성  40%
워라밸      30%
연봉        20%
안정성      10%
```

When a slider moves:

1. Recalculate immediately in frontend or backend.
2. Update option scores.
3. Animate changes on the map.
4. Generate a short explanatory message only when needed.

Important:

The score recalculation must be deterministic. Do not ask the LLM to invent the final numerical score.

---

# 4. Product Philosophy

## 4.1 The AI does not make the final decision

Avoid language such as:

- "무조건 이직하세요"
- "집을 사는 것이 정답입니다"

Use language such as:

- "현재 입력한 조건과 우선순위에서는 A가 더 높은 점수를 받았습니다."
- "워라밸의 중요도를 높이면 B가 더 유리해집니다."
- "결과는 입력된 정보와 가정에 따라 달라질 수 있습니다."

## 4.2 Explainability

Every important value should be marked as one of:

- **사용자 제공 사실**
- **AI 추론**
- **사용자 가정**

Suggested UI markers:

```text
🟢 사용자 입력
🟡 AI 추론
🔵 사용자 가정
```

This should become one of the product's differentiators.

---

# 5. Recommended Technology Stack

## Frontend

- Next.js
- TypeScript
- Tailwind CSS
- React Flow for MVP Decision Map visualization
- Optional D3 later for advanced visualization

Why React Flow first:

- Faster to implement interactive nodes and edges
- Built-in dragging, zooming, viewport control
- Good enough for an attractive competition demo

Do **not** start with Three.js.

## Backend

- **Spring Boot**
- **Kotlin preferred**, Java acceptable if faster
- Spring Web
- Spring Data JPA
- Validation
- Jackson
- Flyway or Liquibase for migrations

Optional:

- Spring AI if it helps structured model integration

Do not adopt a new abstraction layer if it slows the MVP.

## Database

- Existing MySQL

## Cache

- Redis optional

Do not require Redis for the first working release.

## AI

Use an external commercial LLM API.

Do not self-host an LLM for the competition MVP.

Reasons:

- No GPU infrastructure work
- Better response quality
- Faster implementation
- Easier structured output
- Lower operational risk

## Deployment

Reuse the existing Linux server.

Existing environment assumptions:

- Linux server
- Docker installed
- Multiple other projects already running
- MySQL available
- Nginx available or can be configured

Use a separate Docker Compose project and Docker network.

---

# 6. High-Level Architecture

```text
                            User
                              │
                              ▼
                         Cloudflare
                              │
                              ▼
                            Nginx
                              │
                ┌─────────────┴─────────────┐
                ▼                           ▼
          Next.js Web                 Spring Boot API
                                             │
                              ┌──────────────┼──────────────┐
                              ▼              ▼              ▼
                            MySQL       External LLM    Redis(optional)
                                             │
                                             ▼
                                      Decision State
                                             │
                                             ▼
                                      Decision Engine
                                             │
                                             ▼
                                     Decision Map JSON
```

Important rule:

```text
Browser -> Spring Boot -> LLM API
```

Never expose an LLM API key in the frontend.

---

# 7. AI Responsibilities

AI must be used for tasks that genuinely require language understanding.

## 7.1 Conversation AI

Responsibilities:

- Understand the decision topic
- Identify current options
- Ask the next most useful question
- Avoid repetitive questions
- Detect missing information
- Understand user values and preferences

## 7.2 Decision Extraction AI

Convert the conversation into structured data.

Example:

```json
{
  "decisionTitle": "이사 여부",
  "options": [
    {
      "id": "move",
      "name": "이사"
    },
    {
      "id": "stay",
      "name": "현재 집 유지"
    }
  ],
  "criteria": [
    {
      "id": "education",
      "name": "교육 환경",
      "weight": 0.30
    },
    {
      "id": "cost",
      "name": "주거 비용",
      "weight": 0.25
    },
    {
      "id": "commute",
      "name": "통근",
      "weight": 0.20
    }
  ],
  "missingInformation": [
    "이사 후 예상 월 주거비"
  ]
}
```

## 7.3 Insight AI

After deterministic scoring, provide explanations such as:

- What most influenced the score
- Which criterion creates the biggest trade-off
- What assumption could flip the result
- What additional information would reduce uncertainty

## 7.4 Scenario AI

Generate clearly labeled scenario descriptions.

Never present speculative future events as facts.

Example:

> 이 시나리오는 사용자가 입력한 조건을 기반으로 구성된 참고용 시뮬레이션입니다.

---

# 8. Decision State

Decision State is the key technical concept of the application.

Do not treat the chat history as the only source of truth.

Maintain a structured state after each important user response.

Example:

```json
{
  "sessionId": "uuid",
  "title": "이사 여부",
  "stage": "COLLECTING_INFO",
  "options": [
    "이사",
    "현재 집 유지"
  ],
  "criteria": [
    {
      "name": "교육 환경",
      "weight": 0.30,
      "confidence": 0.9
    }
  ],
  "knownFacts": [],
  "assumptions": [],
  "missingInformation": [
    "예상 주거비"
  ],
  "progress": 65
}
```

Recommended stages:

```text
STARTED
IDENTIFYING_OPTIONS
COLLECTING_CRITERIA
COLLECTING_INFO
READY_TO_ANALYZE
ANALYZED
```

---

# 9. Decision Engine

The Decision Engine must be implemented in Kotlin/Java, not delegated to the LLM.

## 9.1 Weighted scoring

For each option:

```text
score(option) = Σ criterionScore × criterionWeight
```

Normalize all weights to total 1.0.

Normalize criterion scores to 0-100.

Example:

```text
Growth     90 × 0.40 = 36
Work-life  70 × 0.30 = 21
Salary     85 × 0.20 = 17
Stability  60 × 0.10 =  6
                         --
                         80
```

## 9.2 Confidence

Do not use the word "confidence" as if the system knows the future.

Recommended meaning:

> 데이터 완성도 / 판단 근거 충실도

Potential calculation:

```text
analysisCompleteness =
  knownRequiredFields / totalRequiredFields
```

Display separately from option score.

## 9.3 What-if

When weights change:

- Do not call the LLM
- Recalculate instantly
- Return new scores
- Re-render the Decision Map

LLM may optionally generate a short explanation after the score changes.

---

# 10. Backend Domain Model

Recommended package layout:

```text
com.example.decisionmap
├── decision
│   ├── controller
│   ├── service
│   ├── domain
│   ├── repository
│   └── dto
├── conversation
│   ├── controller
│   ├── service
│   └── dto
├── ai
│   ├── client
│   ├── prompt
│   ├── schema
│   └── service
├── scoring
│   ├── DecisionEngine.kt
│   └── WeightNormalizer.kt
├── common
│   ├── exception
│   ├── config
│   └── response
└── auth
```

Core services:

```text
ConversationService
DecisionStateService
DecisionExtractionService
DecisionEngine
InsightService
ScenarioService
```

---

# 11. Database Schema

## 11.1 users

For MVP, anonymous users are acceptable.

```text
id BIGINT PK
anonymous_id VARCHAR(64)
created_at DATETIME
```

## 11.2 decision_sessions

```text
id BIGINT PK
public_id VARCHAR(36) UNIQUE
user_id BIGINT NULL
title VARCHAR(255)
stage VARCHAR(50)
progress INT
summary TEXT
created_at DATETIME
updated_at DATETIME
```

## 11.3 messages

```text
id BIGINT PK
session_id BIGINT
role VARCHAR(20)
content TEXT
created_at DATETIME
```

role:

```text
USER
ASSISTANT
SYSTEM
```

## 11.4 decision_options

```text
id BIGINT PK
session_id BIGINT
option_key VARCHAR(100)
name VARCHAR(255)
summary TEXT
score DECIMAL(5,2)
created_at DATETIME
```

## 11.5 decision_criteria

```text
id BIGINT PK
session_id BIGINT
criterion_key VARCHAR(100)
name VARCHAR(255)
weight DECIMAL(6,5)
source_type VARCHAR(30)
created_at DATETIME
```

source_type:

```text
USER_FACT
AI_INFERENCE
USER_ASSUMPTION
```

## 11.6 criterion_option_scores

```text
id BIGINT PK
criterion_id BIGINT
option_id BIGINT
score DECIMAL(5,2)
reason TEXT
source_type VARCHAR(30)
```

## 11.7 decision_insights

```text
id BIGINT PK
session_id BIGINT
insight_type VARCHAR(50)
title VARCHAR(255)
content TEXT
priority INT
```

---

# 12. API Specification

All APIs should be prefixed with:

```text
/api/v1
```

## 12.1 Start decision

```http
POST /api/v1/decisions
```

Request:

```json
{
  "message": "아이 때문에 이사를 고민하고 있어. 이사할지 그냥 살지 모르겠어."
}
```

Response:

```json
{
  "sessionId": "uuid",
  "assistantMessage": "좋아요. 가족의 생활과 비용을 함께 정리해볼게요...",
  "progress": 10,
  "state": {
    "stage": "IDENTIFYING_OPTIONS"
  }
}
```

## 12.2 Send message

```http
POST /api/v1/decisions/{sessionId}/messages
```

Request:

```json
{
  "message": "교육환경이 가장 중요해."
}
```

Response:

```json
{
  "assistantMessage": "교육 환경을 가장 중요하게 생각하시는군요...",
  "progress": 55,
  "decisionState": {}
}
```

## 12.3 Get state

```http
GET /api/v1/decisions/{sessionId}/state
```

## 12.4 Analyze

```http
POST /api/v1/decisions/{sessionId}/analyze
```

Response:

```json
{
  "status": "ANALYZED",
  "result": {
    "options": [],
    "criteria": [],
    "scores": [],
    "insights": []
  }
}
```

## 12.5 Get result

```http
GET /api/v1/decisions/{sessionId}/result
```

## 12.6 What-if

```http
POST /api/v1/decisions/{sessionId}/simulate
```

Request:

```json
{
  "weights": {
    "education": 0.40,
    "cost": 0.25,
    "commute": 0.20,
    "environment": 0.10,
    "stability": 0.05
  }
}
```

Response:

```json
{
  "scores": [
    {
      "optionId": "move",
      "score": 74.2
    },
    {
      "optionId": "stay",
      "score": 68.1
    }
  ],
  "changedLeader": false
}
```

---

# 13. LLM Prompt Design

## 13.1 System prompt

Use a system prompt conceptually similar to:

```text
You are an AI decision facilitator.

Your job is NOT to make decisions for the user.
Your job is to help the user structure a difficult choice.

You must:
1. Understand the user's actual decision.
2. Identify the available options.
3. Identify important decision criteria.
4. Ask one high-value question at a time.
5. Ask only questions necessary for the decision.
6. Infer priorities only when supported by the conversation.
7. Clearly separate facts, user assumptions, and AI inference.
8. Never invent factual information about the user's situation.
9. Never present uncertain outcomes as guaranteed facts.
10. Maintain a structured Decision State.
11. When enough information exists, mark the session ready for analysis.

Keep responses concise and conversational.
```

## 13.2 Structured output

The extraction call should return JSON conforming to a strict schema.

Required fields:

```text
decisionTitle
summary
options[]
criteria[]
knownFacts[]
assumptions[]
aiInferences[]
missingInformation[]
readyToAnalyze
nextQuestion
```

Do not parse arbitrary free-text if the selected LLM supports structured JSON output.

---

# 14. Frontend Components

Recommended structure:

```text
src/
├── app/
│   ├── page.tsx
│   ├── decision/[id]/page.tsx
│   └── result/[id]/page.tsx
├── components/
│   ├── landing/
│   │   ├── HeroDecisionInput.tsx
│   │   └── ScenarioCards.tsx
│   ├── conversation/
│   │   ├── ChatPanel.tsx
│   │   ├── MessageBubble.tsx
│   │   ├── DecisionProgress.tsx
│   │   └── DecisionStatePanel.tsx
│   ├── result/
│   │   ├── DecisionMap.tsx
│   │   ├── OptionScoreCard.tsx
│   │   ├── CriteriaWeights.tsx
│   │   ├── ComparisonTable.tsx
│   │   ├── InsightCards.tsx
│   │   └── WhatIfPanel.tsx
│   └── shared/
├── lib/
│   ├── api.ts
│   └── types.ts
└── styles/
```

---

# 15. Decision Map Visualization

Use **React Flow** for MVP.

Suggested node types:

- Root decision node
- Option node
- Criterion node
- Insight node optional

Suggested map layout:

```text
                    [Decision]
                    /        \
                   /          \
              [Option A]    [Option B]
               /  |  \       / |  \
             C1  C2  C3     C1 C2 C3
```

Node properties:

- label
- score
- importance
- impact direction
- source type

Visual behavior:

- Higher weight -> larger or stronger criterion node
- Higher option score -> more prominent option node
- Hover -> show reason
- Click -> open detail drawer
- What-if -> animate score/size changes

Avoid excessive 3D effects for MVP.

---

# 16. Deployment Plan

## 16.1 Docker Compose

Recommended services:

```text
decision-map-web
decision-map-api
```

Reuse the existing MySQL instance if safely separable by database and credentials.

Optional later:

```text
decision-map-redis
```

## 16.2 Suggested ports

Choose ports that do not collide with existing applications.

Example only:

```text
Next.js internal: 3100
Spring Boot internal: 8100
```

Do not expose database ports publicly.

## 16.3 Nginx

Example routing concept:

```text
https://decision.example.com       -> Next.js
https://decision.example.com/api   -> Spring Boot
```

Alternative:

```text
https://decision.example.com       -> Next.js
https://api-decision.example.com   -> Spring Boot
```

Same-domain `/api` routing is preferable for the MVP because CORS configuration is simpler.

## 16.4 Secrets

Environment variables:

```text
MYSQL_URL
MYSQL_USERNAME
MYSQL_PASSWORD
LLM_API_KEY
LLM_MODEL
NEXT_PUBLIC_API_BASE_URL
```

Never commit `.env` files.

---

# 17. AWS Decision

Do **not** introduce AWS for the first MVP unless a concrete need appears.

Current Linux server + external LLM API is sufficient.

AWS becomes useful later for:

- large traffic
- managed RDS
- object storage
- queues
- GPU inference
- autoscaling

None of these are required to demonstrate the competition concept.

---

# 18. Scope - Must Have

The first deployable MVP must include:

- [ ] Landing page
- [ ] Generic decision input
- [ ] Example scenario cards
- [ ] LLM conversation
- [ ] One-question-at-a-time flow
- [ ] Decision State
- [ ] Options extraction
- [ ] Criteria extraction
- [ ] Criteria weights
- [ ] Missing-information detection
- [ ] Deterministic Decision Engine
- [ ] Decision Map visualization
- [ ] Option comparison
- [ ] AI insight summary
- [ ] What-if sliders
- [ ] Real-time score recalculation
- [ ] MySQL persistence
- [ ] Docker deployment
- [ ] HTTPS public URL
- [ ] Mobile responsive layout
- [ ] Graceful LLM API error state

---

# 19. Scope - Do Not Build This Week

Do not spend time on:

- social login
- payment
- admin panel
- vector DB
- RAG
- PDF upload
- image upload
- voice input
- voice output
- multi-agent orchestration
- self-hosted LLM
- GPU infrastructure
- Three.js 3D world
- advanced analytics
- complex user profiles
- organization/team features

These can be described as future expansion if useful.

---

# 20. Development Schedule

## Day 1 - Project foundation

Goal: Public skeleton deployable.

Tasks:

- [ ] Create frontend repository/project
- [ ] Create Spring Boot project
- [ ] Kotlin or Java selection
- [ ] Configure Docker Compose
- [ ] Configure MySQL schema/database
- [ ] Add Flyway/Liquibase
- [ ] Build landing page
- [ ] Build health endpoint
- [ ] Deploy first version to Linux server
- [ ] Configure Nginx + HTTPS

Definition of done:

```text
Public URL loads correctly.
Frontend can call backend health API.
```

## Day 2 - Conversation engine

- [ ] LLM client integration
- [ ] Decision session API
- [ ] Message API
- [ ] Chat UI
- [ ] Message persistence
- [ ] Prompt v1
- [ ] Decision State schema
- [ ] State extraction after each user turn
- [ ] Progress UI

Definition of done:

A user can type a decision and have a coherent multi-turn conversation.

## Day 3 - Decision Engine

- [ ] Options extraction
- [ ] Criteria extraction
- [ ] Weight normalization
- [ ] Criterion-option scoring model
- [ ] Deterministic weighted scoring
- [ ] Analysis endpoint
- [ ] Result persistence

Definition of done:

The system can produce structured analysis JSON after the conversation.

## Day 4 - Decision Map

- [ ] Install React Flow
- [ ] Root node
- [ ] Option nodes
- [ ] Criterion nodes
- [ ] Edge layout
- [ ] Score cards
- [ ] Comparison table
- [ ] Result page polish

Definition of done:

The result is visually distinct from a chatbot.

## Day 5 - What-if + Insight

- [ ] Weight sliders
- [ ] Real-time score recalculation
- [ ] Animated map update
- [ ] AI insight generation
- [ ] Facts vs inference markers
- [ ] Scenario summary

Definition of done:

Changing priorities visibly changes the decision result.

## Day 6 - QA + competition polish

- [ ] Mobile responsive QA
- [ ] Desktop QA
- [ ] Retry logic
- [ ] LLM timeout handling
- [ ] Empty state
- [ ] Session refresh recovery
- [ ] Add housing demo scenario
- [ ] Add career demo scenario
- [ ] Add travel demo scenario
- [ ] Improve landing page copy
- [ ] Add footer disclaimer

Definition of done:

A first-time external user can complete the flow without explanation.

---

# 21. Demo Script for Judges

Target demo time: 30-60 seconds.

## Demo

User enters:

> 아이 때문에 이사를 고민하고 있어. 지금 집을 팔고 다른 지역으로 갈지 그냥 살지 모르겠어.

AI asks a few short questions about:

- education
- commute
- housing cost
- stability
- living environment

Then:

> **결정 지도 만들기**

Show map.

Example result:

```text
이사 74
유지 68
```

AI says:

> 현재 입력한 조건에서는 교육 환경과 장기 생활 만족도가 이사 쪽 점수를 높였습니다.

Then increase commute weight.

Result changes:

```text
이사 66
유지 72
```

Final product message:

> **AI가 결정을 대신하지 않습니다. 무엇을 중요하게 생각하는지 보이게 합니다.**

This is the core competition moment.

---

# 22. UX Copy Principles

Preferred tone:

- concise
- calm
- non-authoritative
- analytical but friendly

Avoid:

- "정답"
- "무조건"
- "최고의 선택"
- overly long AI messages

Preferred:

- "현재 조건에서는"
- "입력한 우선순위를 기준으로"
- "이 가정을 바꾸면 결과가 달라질 수 있습니다"
- "추가 정보가 있으면 분석 정확도를 높일 수 있습니다"

---

# 23. Safety and Trust

This service should not be positioned as professional medical, legal, financial, or investment advice.

For high-stakes decisions, show a disclaimer such as:

> 이 분석은 사용자가 입력한 정보와 AI 추론을 기반으로 한 의사결정 보조 자료입니다. 중요한 법률·의료·재무 결정은 관련 전문가의 조언과 함께 검토하세요.

Do not request unnecessary sensitive personal information.

---

# 24. Metrics for MVP

Optional internal metrics:

- Decision session started
- Reached READY_TO_ANALYZE
- Analysis completed
- What-if used
- Result shared

Most important funnel:

```text
Landing -> Conversation -> Analyze -> Decision Map -> What-if
```

---

# 25. Future Expansion

Mention for competition scalability, but do not implement now.

Possible roadmap:

- saved decision history
- shared decisions with family/team
- collaborative weighting
- external factual data connectors
- product/price comparisons
- location-aware housing/travel decision modules
- team decision mode
- evidence-based research mode
- decision templates
- scenario history and comparison

Potential B2B use cases:

- feature prioritization
- hiring decisions
- project prioritization
- vendor selection
- strategy alternatives

---

# 26. Codex Working Rules

When coding this project, follow these rules.

## 26.1 General

1. Prioritize a working MVP over abstraction.
2. Do not introduce new infrastructure unless required.
3. Keep AI calls isolated behind a service interface.
4. Keep deterministic scoring independent of the LLM.
5. Use DTOs and strict validation for all AI structured outputs.
6. Always preserve the distinction between user fact, assumption, and AI inference.
7. Avoid building features outside the MVP scope.

## 26.2 Backend

- Use Spring Boot.
- Prefer Kotlin unless Java is chosen explicitly for speed.
- Use package-by-domain structure.
- Use JPA only where it keeps implementation simple.
- Use Flyway or Liquibase.
- Add integration tests for DecisionEngine.
- Add controller tests only for the main flow if time allows.

## 26.3 Frontend

- Use Next.js + TypeScript.
- Keep the landing page visually strong.
- Use React Flow for graph/map rendering.
- The result screen should receive more design attention than the chat screen.
- Avoid overusing animations; use them only to make the analysis transition and What-if state changes clear.

## 26.4 AI

- Use structured JSON output whenever possible.
- Validate AI output server-side.
- Retry once for malformed output or timeout.
- Never let the LLM directly define the final deterministic result score.
- Store prompt version in logs or metadata when practical.

---

# 27. Suggested Initial Repository Structure

Option A - monorepo:

```text
ai-decision-map/
├── frontend/
│   └── Next.js
├── backend/
│   └── Spring Boot
├── docker-compose.yml
├── nginx/
│   └── decision-map.conf
├── docs/
│   └── architecture.md
├── .env.example
└── README.md
```

Option B - separate repositories is also acceptable if preferred.

For competition speed, monorepo is recommended unless existing CI/CD conventions strongly favor separate repositories.

---

# 28. First Codex Task

Start from this exact task:

```text
Read AI_Decision_Map_Codex_Project_Spec.md and scaffold the MVP as a monorepo.

Requirements:
- frontend: Next.js + TypeScript + Tailwind CSS
- backend: Spring Boot + Kotlin + Gradle
- database: existing external MySQL, configured through environment variables
- local orchestration: Docker Compose
- backend health endpoint: GET /api/v1/health
- frontend landing page with the Korean headline "결정하기 어려운 순간, AI와 이야기해보세요."
- decision input field and six example scenario cards: 주거, 여행, 커리어, 구매, 학습, 비즈니스
- configure frontend to call backend through NEXT_PUBLIC_API_BASE_URL
- create .env.example files only; do not commit secrets
- add README instructions for local run and Linux Docker deployment

Do not implement authentication, Redis, RAG, vector databases, Three.js, or advanced AI features yet.
After scaffolding, run builds/tests for both frontend and backend and fix any errors.
```

---

# 29. Second Codex Task

After the first scaffold passes:

```text
Implement the Decision Session and Conversation MVP described in AI_Decision_Map_Codex_Project_Spec.md.

Requirements:
- create decision_sessions and messages migrations
- POST /api/v1/decisions
- POST /api/v1/decisions/{sessionId}/messages
- GET /api/v1/decisions/{sessionId}/state
- implement DecisionState DTO
- implement an LlmClient interface so the actual provider can be swapped
- add one concrete external LLM API implementation selected via environment variables
- require structured JSON output for decision-state extraction
- validate all AI responses server-side
- store user and assistant messages in MySQL
- add retry-once handling for malformed JSON / transient LLM errors
- build the Next.js chat interface and left-side progress/state panel
- do not implement final scoring or Decision Map yet

Add tests for state parsing and the main conversation service.
Run all tests and builds before stopping.
```

---

# 30. Third Codex Task

```text
Implement the deterministic Decision Engine and result API.

Requirements:
- options, criteria, criterion_option_scores, decision_insights tables
- normalize criterion weights
- calculate option scores using weighted scoring in Kotlin
- never use the LLM for the final numeric score calculation
- POST /api/v1/decisions/{sessionId}/analyze
- GET /api/v1/decisions/{sessionId}/result
- add source types USER_FACT, USER_ASSUMPTION, AI_INFERENCE
- add completeness metadata separate from option scores
- generate AI explanations only after deterministic scores are calculated
- add unit tests covering weight normalization, scoring, ties, missing data, and extreme weights

Run tests and fix all failures.
```

---

# 31. Fourth Codex Task

```text
Implement the competition-facing Decision Map result screen.

Requirements:
- use React Flow
- center root Decision node
- option nodes under the root
- criterion nodes connected to options
- display option scores
- show source type markers for relevant reasoning
- comparison tab
- AI insight tab
- responsive desktop and mobile behavior
- polished white/light interface with purple/blue accent tones
- transitions should be subtle and functional

Prioritize readability and visual impact for a competition judge.
```

---

# 32. Fifth Codex Task

```text
Implement What-if simulation.

Requirements:
- criteria weight sliders
- keep weights normalized to 100%
- recalculate scores without calling the LLM
- update option cards and Decision Map immediately
- POST /api/v1/decisions/{sessionId}/simulate is optional if calculation is shared server-side; choose the simplest maintainable architecture
- show a short message when the leading option changes
- add tests for weight changes and score recalculation
```

---

# 33. Final Competition Checklist

Before submission, verify:

- [ ] Public HTTPS URL works
- [ ] No API keys exposed in browser
- [ ] MySQL credentials are private
- [ ] Landing page explains product within 5 seconds
- [ ] Generic decisions work, not just job-change examples
- [ ] Housing demo is polished
- [ ] Conversation does not feel like a rigid questionnaire
- [ ] AI stops asking questions when enough information exists
- [ ] Decision Map is visually memorable
- [ ] What-if clearly changes the outcome
- [ ] AI never presents uncertain predictions as facts
- [ ] Mobile layout works
- [ ] Refreshing does not lose an active saved session
- [ ] LLM timeout has a graceful error state
- [ ] Service remains reachable during judging
- [ ] Submission copy accurately describes the actual AI/API/tools used

---

# 34. Final Product Message

Use this idea consistently in landing copy, demo, and competition submission:

> **AI가 결정을 대신하지 않습니다.**
>
> **당신이 무엇을 중요하게 생각하는지 보이게 합니다.**

