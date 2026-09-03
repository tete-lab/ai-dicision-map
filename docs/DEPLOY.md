# AI Decision Map 배포 준비

운영 구성은 호스트 Nginx가 HTTPS를 종료하고, Docker Compose가 `127.0.0.1:3007`의 프런트엔드와 `127.0.0.1:8013`의 백엔드를 실행하는 방식입니다. 데이터베이스는 기존 MySQL `218.156.22.171:9001`을 사용하며 Compose에서 MySQL 포트를 공개하지 않습니다.

## 1. 서버 최초 준비

Ubuntu 계열 서버를 기준으로 Git, Docker Engine, Docker Compose 플러그인과 Nginx를 설치합니다. 배포 계정은 Docker를 실행할 권한이 있어야 합니다.

```bash
mkdir -p /home/$USER/ai-decision-map/config
cd /home/$USER/ai-decision-map
cp config/app.env.example config/app.env
chmod 600 config/app.env
```

`config/app.env`에서 다음 값을 실제 운영값으로 변경합니다.

- `MYSQL_PASSWORD`: `decision_map_app` 계정의 실제 비밀번호
- `LLM_API_KEY`: 서버 전용 OpenAI API 키
- `CORS_ALLOWED_ORIGINS`: 실제 HTTPS 서비스 주소
- `NEXT_PUBLIC_API_BASE_URL`: 같은 도메인의 `/api`를 사용하므로 빈 값 유지

DB 서버 방화벽은 애플리케이션 서버 IP에서 들어오는 `9001/tcp`만 허용하는 것을 권장합니다.

## 2. 수동 배포 확인

```bash
cd /home/$USER/ai-decision-map
docker compose --env-file config/app.env -f docker-compose.prd.yml config
docker compose --env-file config/app.env -f docker-compose.prd.yml up --build -d
curl --fail http://127.0.0.1:8013/api/v1/health
curl --fail http://127.0.0.1:3007/
```

컨테이너 로그는 다음 명령으로 확인합니다.

```bash
docker compose --env-file config/app.env -f docker-compose.prd.yml logs -f --tail=200
```

## 3. Nginx와 HTTPS

[`nginx/decision-map.conf`](../nginx/decision-map.conf)의 `decision.example.com`과 인증서 경로를 실제 도메인으로 변경한 뒤 서버 Nginx 설정에 설치합니다.

```bash
sudo cp nginx/decision-map.conf /etc/nginx/conf.d/ai-decision-map.conf
sudo nginx -t
sudo systemctl reload nginx
```

외부 방화벽에는 일반적으로 `22`, `80`, `443`만 허용합니다. `3007`과 `8013`은 운영 Compose에서 localhost에만 바인딩되므로 외부에 직접 공개되지 않습니다.

## 4. GitHub Actions 준비

저장소 Settings → Secrets and variables → Actions에 다음 Repository secrets를 등록합니다.

- `BACKEND_HOST`: 배포 서버 주소
- `SERVER_USER`: 서버의 배포 계정
- `SSH_PRIVATE_KEY`: 해당 계정에 접속할 전용 개인키 전체 내용

공개키는 서버의 `/home/<SERVER_USER>/.ssh/authorized_keys`에 등록합니다. [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml)은 활성화되어 있으며 `main` push 시 자동 배포합니다. GitHub Actions → Deploy → Run workflow로 수동 실행도 가능합니다.

서버의 `/home/<SERVER_USER>/ai-decision-map/config/app.env`를 먼저 준비하세요. 파일이 없으면 기존 컨테이너를 변경하지 않고 배포를 중단합니다. env 수정 후 실패한 작업을 재실행하거나 Run workflow를 실행하세요. 배포는 설정 검사 → 이미지 빌드 → 컨테이너 교체 → 상태 확인 순서이며, 빌드 전에 기존 컨테이너를 내리지 않습니다.

배포 작업은 `rm: false`로 소스를 복사하므로 서버의 `config/app.env`를 삭제하지 않습니다. 그래도 운영 비밀값은 별도 백업하고 Git에 커밋하지 마세요.

## 5. 배포 후 점검

```bash
curl --fail https://YOUR_DOMAIN/api/v1/health
curl --fail https://YOUR_DOMAIN/
docker compose --env-file config/app.env -f docker-compose.prd.yml ps
```

문제가 발생하면 Actions 실행 로그와 위 컨테이너 로그로 원인을 확인하고, 서버 설정 수정 후 Deploy 작업을 재실행합니다.
