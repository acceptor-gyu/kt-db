package study.db.server.db_engine

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.Select
import study.db.server.db_engine.dto.ParsedQuery
import study.db.server.db_engine.dto.WhereCondition
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.conditional.OrExpression
import net.sf.jsqlparser.expression.operators.relational.*
import net.sf.jsqlparser.schema.Column

import org.springframework.stereotype.Component

/**
 * Parser      : 문법 검사, AST 생성
 *
 * <잘못된 문법 예시>
 * 키워드 오타   |	SELEC * FROM
 * 괄호 불일치	  | SELECT (a FROM t
 * 잘못된 문장 구조  |	SELECT FROM table
 * 잘못된 순서	  |  WHERE SELECT
 * SQL 끝 누락  | 	SELECT * FROM
 *
 * JsqlParser 활용
 */
@Component
class SqlParser {

    /**
     * SQL 문자열을 파싱하여 구조화된 데이터로 변환
     *
     * @param sql 파싱할 SQL 문자열
     * @return ParsedQuery 파싱된 쿼리 정보 (테이블명, WHERE 조건, ORDER BY 등)
     * @throws Exception SQL 파싱 실패 시
     */
    fun parseQuery(sql: String): ParsedQuery {
        // 1. SQL 문자열을 파싱 트리로 변환
        // CCJSqlParserUtil: JSqlParser의 메인 파서 유틸리티
        val statement = CCJSqlParserUtil.parse(sql) as Select

        // 2. PlainSelect 객체 추출 (단순 SELECT 문)
        // PlainSelect: 기본적인 SELECT 문 (UNION, INTERSECT 등이 없는)
        val plainSelect = statement.plainSelect
            ?: throw IllegalArgumentException("Only plain SELECT statements are supported")

        // 3. 테이블명 추출, WHERE절 / ORDER BY절 / SELECT 컬럼 파싱
        val tableName = extractTableName(plainSelect)
        val whereConditions = parseWhereClause(plainSelect.where)
        val orderBy = parseOrderBy(plainSelect)
        val selectColumns = parseSelectColumns(plainSelect)

        return ParsedQuery(
            tableName = tableName,
            selectColumns = selectColumns,
            whereConditions = whereConditions,
            orderBy = orderBy
        )
    }

    /**
     * FROM 절에서 테이블명 추출
     *
     * e.g. "SELECT * FROM users" -> "users"
     * e.g. "SELECT * FROM db.users" -> "users" (스키마 제거)
     */
    private fun extractTableName(plainSelect: PlainSelect): String {
        // fromItem: FROM 절의 첫 번째 항목 (메인 테이블)
        val fromItem = plainSelect.fromItem
            ?: throw IllegalArgumentException("FROM clause is required")

        // toString()으로 테이블명 추출
        // e.g. Table(name=users, alias=null) -> "users"
        return fromItem.toString()
    }

    /**
     * SELECT 절에서 컬럼 목록 추출
     *
     * e.g. "SELECT id, name" -> ["id", "name"]
     * e.g. "SELECT *" -> ["*"]
     * e.g. "SELECT user_id AS uid" -> ["user_id"]
     */
    private fun parseSelectColumns(plainSelect: PlainSelect): List<String> {
        // selectItems: SELECT 다음에 오는 컬럼/표현식 목록
        val selectItems = plainSelect.selectItems ?: return emptyList()

        return selectItems.mapNotNull { item ->
            when {
                // AllColumns: SELECT * 의 경우
                item.toString() == "*" -> "*"

                // 일반 컬럼: SELECT id, name
                // AS 별칭이 있으면 별칭 제거하고 원본 컬럼명만 추출
                else -> {
                    val itemStr = item.toString()
                    // "user_id AS uid" -> "user_id"
                    itemStr.split(" AS ", ignoreCase = true).first().trim()
                }
            }
        }
    }

    /**
     * WHERE 절을 개별 조건으로 분해
     *
     * e.g. "WHERE age > 20 AND status = 'active'"
     *     -> [
     *          WhereCondition("age", ">", "20"),
     *          WhereCondition("status", "=", "'active'")
     *        ]
     */
    private fun parseWhereClause(where: Expression?): List<WhereCondition> {
        // WHERE 절이 없으면 빈 리스트 반환
        if (where == null) return emptyList()

        val conditions = mutableListOf<WhereCondition>()

        // Expression을 재귀적으로 순회하며 조건 추출
        extractConditions(where, conditions)

        return conditions
    }

    /**
     * Expression 트리를 재귀적으로 탐색하여 조건 추출
     *
     * Expression 타입:
     * - AndExpression: "A AND B" 형태
     * - OrExpression: "A OR B" 형태
     * - EqualsTo: "A = B" 형태
     * - GreaterThan: "A > B" 형태
     * - etc.
     */
    private fun extractConditions(
        expressions: Expression,
        conditions: MutableList<WhereCondition>
    ) {
        when (expressions) {
            // AND 연산자: 좌우 표현식 모두 재귀 탐색
            is AndExpression -> {
                // e.g.: "age > 20 AND status = 'active'"
                //     leftExpression: age > 20
                //     rightExpression: status = 'active'
                extractConditions(expressions.leftExpression, conditions)
                extractConditions(expressions.rightExpression, conditions)
            }

            // OR 연산자: 좌우 표현식 모두 재귀 탐색
            is OrExpression -> {
                extractConditions(expressions.leftExpression, conditions)
                extractConditions(expressions.rightExpression, conditions)
            }

            // 등호 (=)
            is EqualsTo -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        "=",
                        expressions.rightExpression
                    )
                )
            }

            // 부등호 (!=, <>)
            is NotEqualsTo -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        "!=",
                        expressions.rightExpression
                    )
                )
            }

            // 크다 (>)
            is GreaterThan -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        ">",
                        expressions.rightExpression
                    )
                )
            }

            // 크거나 같다 (>=)
            is GreaterThanEquals -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        ">=",
                        expressions.rightExpression
                    )
                )
            }

            // 작다 (<)
            is MinorThan -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        "<",
                        expressions.rightExpression
                    )
                )
            }

            // 작거나 같다 (<=)
            is MinorThanEquals -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        "<=",
                        expressions.rightExpression
                    )
                )
            }

            // BETWEEN 연산자
            // e.g. "age BETWEEN 20 AND 30"
            is Between -> {
                val column = expressions.leftExpression.toString()
                val start = expressions.betweenExpressionStart.toString()
                val end = expressions.betweenExpressionEnd.toString()

                conditions.add(
                    WhereCondition(
                        column = column,
                        operator = "BETWEEN",
                        value = "$start AND $end"
                    )
                )
            }

            // LIKE 연산자
            // e.g. "name LIKE 'John%'"
            is LikeExpression -> {
                conditions.add(
                    createCondition(
                        expressions.leftExpression,
                        "LIKE",
                        expressions.rightExpression
                    )
                )
            }

            // IS NULL / IS NOT NULL
            is IsNullExpression -> {
                val column = expressions.leftExpression.toString()
                conditions.add(
                    WhereCondition(
                        column = column,
                        operator = if (expressions.isNot) "IS NOT NULL" else "IS NULL",
                        value = ""
                    )
                )
            }

            // TODO: IN 연산자 (후순위)
        }
    }

    /**
     * 좌변(컬럼)과 우변(값)에서 WhereCondition 객체 생성
     *
     * @param left 좌변 표현식 (보통 컬럼명)
     * @param operator 연산자 (=, >, < 등)
     * @param right 우변 표현식 (보통 값)
     */
    private fun createCondition(
        left: Expression,
        operator: String,
        right: Expression
    ): WhereCondition {
        // Column 타입이면 컬럼명 추출, 아니면 toString()
        val columnName = if (left is Column) {
            left.columnName
        } else {
            left.toString()
        }

        // 값 추출 (따옴표, 공백 제거)
        val value = right.toString().trim()

        return WhereCondition(
            column = columnName,
            operator = operator,
            value = value
        )
    }

    /**
     * ORDER BY 절 파싱
     *
     * e.g. "ORDER BY created_at DESC, name ASC"
     *     -> ["created_at", "name"]
     */
    private fun parseOrderBy(plainSelect: PlainSelect): List<String> {
        // orderByElements: ORDER BY 다음의 컬럼 목록
        val orderByElements = plainSelect.orderByElements ?: return emptyList()

        return orderByElements.map { element ->
            // expression: 정렬할 컬럼/표현식
            // isAsc: 오름차순 여부 (true=ASC, false=DESC)
            element.expression.toString()
        }
    }
}
