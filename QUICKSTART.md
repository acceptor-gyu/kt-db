# 빠른 시작 가이드

Docker Compose를 사용하여 5분 안에 전체 시스템을 실행하고 테스트하는 방법입니다.

## 전제 조건

- Docker Desktop 설치 (Docker Compose 포함)
- 사용 가능한 포트: 8080, 9000, 9200, 5601

## 1단계: 서비스 실행 (2분)

```bash
# 전체 서비스 실행
docker compose up -d --build
```

**실행되는 서비스:**
- ✅ Elasticsearch (9200) - 데이터 저장소
- ✅ Kibana (5601) - 모니터링 대시보드
- ✅ DB Server (9000) - TCP 데이터베이스 서버
- ✅ API Server (8080) - REST API 서버

## 2단계: 서비스 확인 (30초)

```bash
# 모든 서비스가 healthy 상태인지 확인
docker compose ps

# 출력 예시:
# NAME        STATUS              HEALTH
# api-server  Up 1 minute         healthy
# db-server   Up 2 minutes        healthy
# es          Up 2 minutes        healthy
# kibana      Up 2 minutes        healthy
```

모든 서비스가 "healthy" 상태가 될 때까지 기다립니다 (약 30초-1분).

## 3단계: API 테스트 (1분)

### 간단한 테스트

```bash
# 1. DB 서버 연결 확인
curl http://localhost:8080/api/tables/ping

# 응답: {"success":true,"message":"pong"}

# 2. 테이블 생성
curl -X POST http://localhost:8080/api/tables/create \
  -H "Content-Type: application/json" \
  -d '{"query": "CREATE TABLE users (id INT, name VARCHAR, age INT)"}'

# 3. 데이터 삽입
curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{"query": "INSERT INTO users VALUES (id=\"1\", name=\"John\", age=\"30\")"}'

# 4. 데이터 조회
curl -X GET 'http://localhost:8080/api/tables/select?query=SELECT%20*%20FROM%20users'

# 5. 쿼리 플랜 확인
curl -X GET 'http://localhost:8080/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20users'
```

### 자동화된 전체 테스트

```bash
# 5개 테이블, 19개 레코드를 자동으로 생성
cd api-server
./test-api-requests.sh
```

이 스크립트는 다음을 수행합니다:
- ✅ users 테이블 생성 및 4명 삽입
- ✅ products 테이블 생성 및 5개 상품 삽입
- ✅ orders 테이블 생성 및 3개 주문 삽입
- ✅ logs 테이블 생성 및 4개 로그 삽입
- ✅ employees 테이블 생성 및 3명 직원 삽입
- ✅ 각 테이블별 EXPLAIN 쿼리 실행

## 4단계: 데이터 확인 (1분)

### 파일 시스템 확인

```bash
# db-server 컨테이너 접속
docker compose exec db-server sh

# 데이터 파일 확인
ls -lh /app/data/

# 출력 예시:
# users.dat
# products.dat
# orders.dat
# logs.dat
# employees.dat

# 컨테이너 나가기
exit
```

### Kibana에서 확인

브라우저에서 접속: http://localhost:5601

1. 왼쪽 메뉴 → **Discover** 클릭
2. **Create data view** 클릭
3. Index pattern: `db-query-logs*` 입력
4. **Save data view** 클릭
5. 쿼리 로그 확인

## 5단계: 서버 재시작 테스트 (1분)

데이터가 영구 저장되는지 확인:

```bash
# 1. 서버 재시작
docker compose restart db-server api-server

# 2. 데이터 조회 (여전히 존재함)
curl -X GET 'http://localhost:8080/api/tables/select?query=SELECT%20*%20FROM%20users'
```

데이터가 유지됩니다! 🎉

## 정리

### 서비스 중지 (데이터 유지)

```bash
docker compose down
```

다음 실행 시 `docker compose up -d`로 데이터가 그대로 유지됩니다.

### 완전 삭제 (데이터 포함)

```bash
# 볼륨까지 삭제 (모든 데이터 삭제)
docker compose down -v
```

⚠️ **주의**: `-v` 옵션은 모든 테이블 데이터를 삭제합니다!

## 로그 확인

```bash
# 전체 로그
docker compose logs -f

# API 서버 로그만
docker compose logs -f api-server

# DB 서버 로그만
docker compose logs -f db-server

# Elasticsearch 로그
docker compose logs -f elasticsearch
```

## 포트 충돌 해결

만약 포트가 이미 사용 중이라는 에러가 발생하면:

```bash
# 포트 사용 확인
lsof -i :8080  # API Server
lsof -i :9000  # DB Server
lsof -i :9200  # Elasticsearch
lsof -i :5601  # Kibana

# 프로세스 종료
kill -9 <PID>
```

## 다음 단계

### 1. Postman 사용

Postman에서 더 편하게 테스트:

1. Postman 열기
2. Import → `api-server/postman-collection.json`
3. 컬렉션 실행

### 2. 더 많은 예제

```bash
# 샘플 데이터 JSON 파일 확인
cat api-server/test-data.json
```

### 3. 상세 가이드

- **Docker 사용법**: [DOCKER_GUIDE.md](./DOCKER_GUIDE.md)
- **API 사용법**: [api-server/API_USAGE.md](./api-server/API_USAGE.md)
- **테스트 방법**: [api-server/TEST_README.md](./api-server/TEST_README.md)

## 트러블슈팅

### 문제: 서비스가 "unhealthy" 상태

```bash
# 로그 확인
docker compose logs db-server

# 재시작
docker compose restart db-server
```

### 문제: Elasticsearch 메모리 부족

Docker Desktop → Settings → Resources → Memory를 4GB 이상으로 설정

### 문제: 빌드 실패

```bash
# 캐시 없이 재빌드
docker compose build --no-cache
docker compose up -d
```

## 요약

```bash
# 시작
docker compose up -d --build

# 테스트
cd api-server && ./test-api-requests.sh

# 확인
curl http://localhost:8080/api/tables/ping

# 중지
docker compose down
```

**5분 만에 완료!** ✨
