# Docker Compose 사용 가이드

Docker Compose를 사용하여 전체 시스템을 한 번에 실행할 수 있습니다.

## 서비스 구성

Docker Compose는 다음 4개의 서비스를 실행합니다:

| 서비스 | 호스트 포트 | 컨테이너 포트 | 설명 |
|--------|------------|--------------|------|
| **elasticsearch** | 9201, 9301 | 9200, 9300 | Elasticsearch 서버 (EXPLAIN 기능용) |
| **kibana** | 5602 | 5601 | Kibana (Elasticsearch 모니터링) |
| **db-server** | 9001 | 9000 | DB 서버 (TCP 프로토콜) |
| **api-server** | 8081 | 8080 | REST API 서버 (HTTP) |

**포트 충돌 방지**: 호스트 포트는 로컬 실행과 충돌하지 않도록 +1 설정됨

## 실행 방법

### 1. 전체 서비스 실행 (백그라운드)

**BuildKit 활성화 (권장):**
```bash
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
```

**실행:**
```bash
docker compose up -d --build
```

**옵션 설명:**
- `-d`: 백그라운드 실행 (detached mode)
- `--build`: 이미지 빌드 (변경된 서비스만 재빌드)

**실행 순서:**
1. Elasticsearch 시작 및 헬스체크 대기
2. Kibana 시작
3. DB Server 시작 및 헬스체크 대기
4. API Server 시작

**예상 시간 (빌드 최적화 적용):**
- **첫 실행**: 3-5분 (이미지 다운로드 + 전체 빌드)
- **db-server 소스만 수정**: 30초 ⚡ (90% 감소)
- **api-server 소스만 수정**: 30초 ⚡ (90% 감소)
- **의존성 변경 없이 재실행**: 10초 (컨테이너 시작만)

💡 **최적화 팁**: 자세한 빌드 최적화 내용은 [DOCKER_BUILD_GUIDE.md](./DOCKER_BUILD_GUIDE.md)를 참조하세요.

### 2. 로그 확인

**전체 로그 보기:**
```bash
docker compose logs -f
```

**특정 서비스 로그 보기:**
```bash
# API 서버 로그
docker compose logs -f api-server

# DB 서버 로그
docker compose logs -f db-server

# Elasticsearch 로그
docker compose logs -f elasticsearch
```

**최근 100줄만 보기:**
```bash
docker compose logs --tail=100 -f api-server
```

### 3. 서비스 상태 확인

**모든 서비스 상태:**
```bash
docker compose ps
```

**헬스체크 상태 확인:**
```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Health}}"
```

**예상 출력:**
```
NAME        STATUS              HEALTH
api-server  Up 2 minutes        healthy
db-server   Up 2 minutes        healthy
es          Up 3 minutes        healthy
kibana      Up 3 minutes        healthy
```

### 4. 서비스 테스트

**API 서버 연결 확인:**
```bash
curl http://localhost:8081/api/tables/ping
```

**응답 예시:**
```json
{
  "success": true,
  "message": "pong"
}
```

**Elasticsearch 확인:**
```bash
curl http://localhost:9201/_cluster/health
```

**Kibana 접속:**
브라우저에서 `http://localhost:5602` 접속

### 5. 서비스 중지

**전체 서비스 중지 (컨테이너 유지):**
```bash
docker compose stop
```

**전체 서비스 중지 및 삭제:**
```bash
docker compose down
```

**볼륨까지 삭제 (데이터 삭제):**
```bash
docker compose down -v
```

⚠️ **주의:** `-v` 옵션을 사용하면 저장된 모든 테이블 데이터가 삭제됩니다!

### 6. 서비스 재시작

**전체 서비스 재시작:**
```bash
docker compose restart
```

**특정 서비스만 재시작:**
```bash
# API 서버만 재시작
docker compose restart api-server

# DB 서버만 재시작
docker compose restart db-server
```

### 7. 특정 서비스만 실행

**Elasticsearch만 실행:**
```bash
docker compose up -d elasticsearch
```

**DB 서버만 재빌드 및 실행:**
```bash
docker compose up -d --build db-server
```

## 데이터 영속성

### 데이터 저장 위치

Docker Volume을 사용하여 데이터를 영구 저장합니다:

- **elasticsearch-data**: Elasticsearch 데이터
- **db-server-data**: 테이블 데이터 (`/app/data/*.dat`)

### 볼륨 확인

```bash
# 볼륨 목록 확인
docker volume ls | grep db

# 볼륨 상세 정보
docker volume inspect db_db-server-data
```

### 볼륨 백업

```bash
# DB 서버 데이터 백업
docker run --rm -v db_db-server-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/db-data-backup.tar.gz -C /data .

# 백업 복원
docker run --rm -v db_db-server-data:/data -v $(pwd):/backup \
  alpine tar xzf /backup/db-data-backup.tar.gz -C /data
```

## 트러블슈팅

### 문제 1: 포트 충돌

**에러:**
```
Error response from daemon: Ports are not available
```

**해결:**
포트가 이미 사용 중입니다. 실행 중인 프로세스 확인:
```bash
# 포트 8081 사용 확인 (API Server)
lsof -i :8081

# 포트 9001 사용 확인 (DB Server)
lsof -i :9001

# 포트 9201 사용 확인 (Elasticsearch)
lsof -i :9201

# 기존 프로세스 종료
kill -9 <PID>
```

### 문제 2: 빌드 실패

**에러:**
```
failed to solve: process "/bin/sh -c ./gradlew ..." did not complete successfully
```

**해결 1: 캐시 초기화 (권장)**
```bash
# BuildKit 캐시 정리
docker builder prune -af

# 재빌드
docker compose build --no-cache
docker compose up -d
```

**해결 2: BuildKit 활성화 확인**
```bash
# BuildKit이 비활성화되어 있으면 최적화가 작동하지 않음
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

# 재빌드
docker compose build
docker compose up -d
```

### 문제 3: 서비스가 unhealthy 상태

**확인:**
```bash
docker compose ps
docker compose logs db-server
```

**해결:**
- Elasticsearch가 먼저 시작되었는지 확인
- 로그에서 에러 메시지 확인
- 필요시 서비스 재시작: `docker compose restart db-server`

### 문제 4: 데이터가 사라짐

**원인:** `docker compose down -v` 실행 시 볼륨 삭제

**해결:**
- 볼륨을 삭제하지 않고 중지: `docker compose down` (without `-v`)
- 정기적으로 백업 수행

### 문제 5: 메모리 부족

**에러:**
```
elasticsearch exited with code 137
```

**해결:**
Docker Desktop 설정에서 메모리 증가:
- Docker Desktop → Settings → Resources
- Memory를 4GB 이상으로 설정

## API 테스트 (Docker 환경)

### 전체 테스트 스크립트 실행

```bash
# 서비스가 모두 healthy 상태인지 확인
docker compose ps

# 테스트 스크립트 실행
cd api-server
./test-api-requests.sh
```

### 개별 테스트

```bash
# 1. CREATE TABLE
curl -X POST http://localhost:8081/api/tables/create \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE users (id INT, name VARCHAR, age INT)"
  }'

# 2. INSERT
curl -X POST http://localhost:8081/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"1\", name=\"John\", age=\"30\")"
  }'

# 3. SELECT
curl -X GET 'http://localhost:8081/api/tables/select?query=SELECT%20*%20FROM%20users'

# 4. EXPLAIN
curl -X GET 'http://localhost:8081/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20users'
```

### 데이터 확인 (컨테이너 내부)

```bash
# db-server 컨테이너 접속
docker compose exec db-server sh

# 데이터 파일 확인
ls -lh /app/data/

# 컨테이너 나가기
exit
```

## 개발 워크플로우

### 코드 변경 후 재실행

**변경된 서비스만 자동 재빌드 (권장):**
```bash
# 변경 사항을 감지하여 필요한 서비스만 재빌드
docker compose up -d --build
```

**특정 서비스만 재빌드:**
```bash
# db-server만 수정했을 때
docker compose build db-server
docker compose up -d db-server

# api-server만 수정했을 때
docker compose build api-server
docker compose up -d api-server
```

**빌드 없이 재시작만 (코드 변경 없을 때):**
```bash
docker compose restart db-server
```

💡 **성능 팁**:
- db-server만 수정 시 api-server는 재빌드되지 않습니다 (레이어 캐싱)
- 소스 코드만 변경 시 의존성 다운로드는 캐시됩니다 (2분 절약)
- 자세한 내용: [DOCKER_BUILD_GUIDE.md](./DOCKER_BUILD_GUIDE.md)

### 로컬 개발과 Docker 병행

**로컬에서 개발:**
```bash
# DB 서버만 Docker로 실행
docker compose up -d db-server elasticsearch

# API 서버는 로컬에서 실행
./gradlew :api-server:bootRun
```

**Docker에서 전체 실행:**
```bash
docker compose up -d --build
```

## 프로덕션 배포

### 환경 변수 설정

`.env` 파일 생성:
```env
# Elasticsearch
ES_JAVA_OPTS=-Xms1g -Xmx1g

# DB Server
DB_STORAGE_DIR=/app/data
DB_SERVER_PORT=9000

# API Server
API_SERVER_PORT=8080
```

docker-compose.yml에서 사용:
```yaml
services:
  elasticsearch:
    environment:
      - "ES_JAVA_OPTS=${ES_JAVA_OPTS}"
```

### 리소스 제한

```yaml
services:
  db-server:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
        reservations:
          cpus: '0.5'
          memory: 256M
```

## 유용한 명령어 모음

```bash
# 전체 로그 저장
docker compose logs > logs.txt

# 특정 서비스 재빌드
docker compose build --no-cache api-server

# 사용하지 않는 리소스 정리
docker system prune -a

# 실행 중인 컨테이너에서 명령 실행 (컨테이너 내부 포트 사용)
docker compose exec api-server curl localhost:8080/api/tables/ping

# 이미지 크기 확인
docker images | grep db

# 네트워크 확인
docker network ls | grep db
docker network inspect db_db-network
```

## 요약

**시작:**
```bash
docker compose up -d --build
```

**상태 확인:**
```bash
docker compose ps
curl http://localhost:8081/api/tables/ping
```

**테스트:**
```bash
cd api-server && ./test-api-requests.sh
```

**중지:**
```bash
docker compose down
```

**데이터 포함 완전 삭제:**
```bash
docker compose down -v
```
