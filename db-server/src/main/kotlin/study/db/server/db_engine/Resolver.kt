package study.db.server.db_engine

/**
 * Resolver    : 이름 해석, 타입 결정, 의미 확정
 *
 * 에러 상황	 | 에러 예시
 * 테이블 없음 | 	Table doesn't exist
 * 컬럼 없음  |	Unknown column
 * 별칭 오류  |	Unknown table alias
 * 중복 컬럼  |	Column ambiguous
 * DB 미선택  |	No database selected
 */
class Resolver {
}