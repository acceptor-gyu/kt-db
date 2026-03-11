# api-server

HTTP REST API 서버 모듈. db-server(TCP)에 대한 클라이언트 역할을 하며, 외부 사용자에게 HTTP 인터페이스를 제공한다.

## 구조

```
src/main/kotlin/study/db/api/
├── ApiServerApplication.kt       # Spring Boot 진입점
├── client/
│   └── DbClient.kt              # db-server TCP 클라이언트 (ProtocolCodec 사용)
├── controller/
│   └── TableController.kt       # REST API 엔드포인트 (SQL 실행, EXPLAIN 등)
└── dto/
    ├── SqlQueryRequest.kt        # SQL 요청 DTO
    └── FindQueryPlanResponse.kt  # EXPLAIN 응답 DTO
```

## 역할

- HTTP 요청을 받아 SQL 문자열로 변환 후 TCP를 통해 db-server에 전달
- db-server의 응답(JSON)을 HTTP 응답으로 반환
- 기본 포트: 8080 (Docker: 8081)

## 주요 의존성

- `common` 모듈: ProtocolCodec, DbResponse 등 공유 프로토콜
- Spring Boot Web
