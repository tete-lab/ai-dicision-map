# AI Decision Map

AI와 대화하며 고민의 선택지와 판단 기준을 구조화하는 의사결정 코칭 서비스입니다. 사용자의 말에서 드러난 마음의 방향과 실제 점수를 함께 해석하고, 근거가 허용하는 범위에서 긍정적인 다음 행동을 제안합니다.

현재 저장소에는 MVP 모노레포 스캐폴드와 Decision Session 대화 API가 구현되어 있습니다.

```text
ai-decision-map/
├── frontend/          Next.js + TypeScript + Tailwind CSS
├── backend/           Spring Boot + Kotlin + Gradle
├── nginx/             Linux 배포용 Nginx 예시
├── docker-compose.yml
└── .env.example
```

## 요구 사항

- Node.js 20.9 이상 및 npm
- JDK 21
- Docker 및 Docker Compose (컨테이너 실행 시)
- 접근 가능한 MySQL 8 인스턴스

## 로컬 실행

### 1. MySQL 환경 변수 준비

MySQL 관리자 계정으로 먼저 `docs/mysql-bootstrap.sql`을 실행합니다. 이 스크립트는 `ai_decision_map` 데이터베이스와 `decision_map_app` 계정을 만듭니다. 실행 전에 반드시 비밀번호 플레이스홀더를 강한 임의 비밀번호로 바꾸세요.

서버 주소 `218.156.22.171:9001`은 예시 환경 파일에 구성되어 있습니다. 생성한 동일한 비밀번호만 로컬 `.env`에 입력합니다. 예시 파일에는 비밀값이 들어 있지 않습니다.

```bash
cp backend/.env.example backend/.env
# backend/.env의 MYSQL_PASSWORD와 LLM_API_KEY를 실제 값으로 변경
set -a
source backend/.env
set +a
```

MySQL 서버 방화벽에서는 애플리케이션 서버의 접속만 `9001` 포트에 허용하는 것을 권장합니다. 배포 서버 IP가 확정되면 SQL 파일 하단 안내대로 `%` 계정을 해당 IP로 제한하세요. Flyway가 이후 추가되는 테이블 마이그레이션을 관리합니다.

### OpenAI API 키 준비

1. [OpenAI API 키 페이지](https://platform.openai.com/api-keys)에서 프로젝트용 Secret key를 생성합니다.
2. 전체 키는 생성 순간에만 표시되므로 즉시 안전한 곳에 보관합니다.
3. [API 결제 설정](https://platform.openai.com/settings/organization/billing/overview)에서 결제 수단 또는 선불 크레딧을 준비합니다. ChatGPT 구독과 API 결제는 별도입니다.
4. 생성한 키를 `backend/.env`의 `LLM_API_KEY`에 입력합니다. 프런트엔드 환경변수나 Git에는 절대 넣지 않습니다.

### 2. 백엔드 실행

```bash
cd backend
./gradlew bootRun
```

헬스 엔드포인트는 `http://localhost:8013/api/v1/health`입니다.

대화 API는 첫 고민을 `POST /api/v1/decisions`에 `{"message":"..."}`로 보내 세션을 만들고, 이어지는 답변은 `POST /api/v1/decisions/{sessionId}/messages`로 전송합니다. 현재 상태는 `GET /api/v1/decisions/{sessionId}/state`에서 확인할 수 있습니다. OpenAI Responses API 키는 반드시 백엔드의 `LLM_API_KEY`에만 설정하세요.

결정 상태가 준비되면 `POST /api/v1/decisions/{sessionId}/analyze`에 기준·선택지별 0~100 평가값을 전달합니다. 최종 점수와 가중치 정규화는 Kotlin 결정 엔진이 즉시 계산합니다. `POST /api/v1/decisions/{sessionId}/enrich`는 그 결과를 바꾸지 않고 근거형 인사이트와 액션 플랜을 보강합니다. 저장된 결과는 `GET /api/v1/decisions/{sessionId}/result`에서 조회합니다.

## 응원형 결정 코칭 원칙

- 이사·이직처럼 삶에 영향을 주는 결정은 실행했을 때와 유지했을 때의 장점·부담을 모두 비교합니다.
- 사용자가 직접 표현한 기대·안도감·설렘·후회 회피에서 마음의 방향을 찾고, 점수 선두와 8점 이내일 때 그 방향을 우선 응원합니다.
- 점수 차이가 크거나 근거 품질이 낮으면 낙관적인 단정 대신 확인 가능한 작은 실험을 권합니다.
- 결과에는 장단점, 근거 품질, 가중치 변화 시나리오, 완료 조건이 있는 액션 플랜을 함께 표시합니다.

### 3. 프론트엔드 실행

별도 터미널에서 실행합니다.

```bash
cd frontend
cp .env.example .env.local
npm install
npm run dev
```

브라우저에서 `http://localhost:3007`을 엽니다. 프론트엔드는 `NEXT_PUBLIC_API_BASE_URL`의 API를 호출합니다.

## 테스트와 빌드

```bash
cd frontend
npm test
npm run lint
npm run build

cd ../backend
./gradlew test build
```

## Docker Compose 실행

이 Compose 파일은 MySQL 컨테이너를 만들지 않습니다. 기존 외부 MySQL을 사용합니다.

```bash
cp .env.example .env
# .env의 MySQL 주소·비밀번호와 LLM_API_KEY를 실제 값으로 변경
docker compose config
docker compose up --build -d
curl http://localhost:8013/api/v1/health
```

실행 포트는 프론트엔드 `3007`, 백엔드 `8013`입니다. 데이터베이스 포트는 Compose에서 외부로 노출하지 않습니다.

## Linux + Nginx 배포

1. 서버에 저장소를 배치하고 `.env.example`을 `.env`로 복사합니다.
2. MySQL URL, 사용자명, 비밀번호와 허용할 서비스 도메인을 설정합니다.
3. 같은 도메인에서 `/api`를 프록시할 경우 `NEXT_PUBLIC_API_BASE_URL`을 빈 값으로 설정합니다. 이 값은 브라우저 번들에 포함되므로 프론트엔드 이미지를 다시 빌드해야 변경됩니다.
4. 운영 서버에서는 `config/app.env`와 `docker-compose.prd.yml`을 사용해 Compose 프로젝트를 시작합니다.
5. [nginx/decision-map.conf](nginx/decision-map.conf)의 도메인과 인증서 경로를 서버 환경에 맞게 바꿔 Nginx에 적용합니다.
6. `https://your-domain.example/api/v1/health`와 첫 화면을 확인합니다.

운영 서버에서는 `.env`를 커밋하지 말고 권한을 제한하세요. LLM API 키는 백엔드 환경 변수에만 두며 `NEXT_PUBLIC_` 접두사를 사용하지 않습니다.

서버 최초 준비, Nginx 적용, GitHub Secrets와 자동 배포 사용법은 [docs/DEPLOY.md](docs/DEPLOY.md)를 참고하세요. `.github/workflows/deploy.yml`은 `main` push 시 배포하며, Actions 화면에서 수동 실행도 가능합니다. 서버의 `config/app.env`를 먼저 준비해야 합니다.
