package study.db.server.exception

/**
 * 리소스가 이미 존재할 때 발생하는 예외 (409 Conflict)
 *
 * @param resourceType 리소스 타입 (예: "Table", "Index")
 * @param resourceName 리소스 이름
 */
class ResourceAlreadyExistsException(
    val resourceType: String,
    val resourceName: String
) : IllegalStateException(
    "$resourceType '$resourceName' already exists"
)
