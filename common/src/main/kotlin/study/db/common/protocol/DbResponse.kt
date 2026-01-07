package study.db.common.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DbResponse(
    val success: Boolean,
    val message: String? = null,
    val data: String? = null,
    val errorCode: Int? = null
)
