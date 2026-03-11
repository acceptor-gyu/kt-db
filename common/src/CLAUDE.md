# common/src

db-server와 api-server가 공유하는 공통 코드 모듈.

## 구조

```
main/kotlin/study/db/common/
├── Table.kt                  # 테이블 도메인 모델 (tableName, dataType, rows)
├── Row.kt                    # 행 도메인 모델
├── VacuumStats.kt            # Vacuum 통계 데이터
├── protocol/
│   ├── ProtocolCodec.kt      # TCP 프로토콜 인코딩/디코딩 (4바이트 길이 + UTF-8)
│   └── DbResponse.kt         # 서버 응답 모델 (success, message, data)
└── where/
    ├── Condition.kt           # WHERE 조건 모델
    ├── WhereClause.kt         # WHERE 절 파싱
    └── WhereEvaluator.kt      # WHERE 조건 평가
```

## 역할

- **프로토콜**: 클라이언트-서버 간 TCP 통신 규약 (길이 프리픽스 + UTF-8 문자열)
- **도메인 모델**: Table, Row 등 양쪽 모듈에서 사용하는 공통 데이터 구조
- **WHERE 절**: SQL WHERE 조건의 파싱 및 평가 로직
