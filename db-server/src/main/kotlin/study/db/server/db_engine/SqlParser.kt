package study.db.server.db_engine

/**
 * Parser      : 문법 검사, AST 생성
 *
 *
 * 키워드 오타   |	SELEC * FROM
 * 괄호 불일치	  | SELECT (a FROM t
 * 잘못된 문장 구조  |	SELECT FROM table
 * 잘못된 순서	  |  WHERE SELECT
 * SQL 끝 누락  | 	SELECT * FROM
 *
 * JsqlParser 활용
 */
class SqlParser {
}