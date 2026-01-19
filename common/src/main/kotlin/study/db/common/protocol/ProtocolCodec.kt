package study.db.common.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object ProtocolCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 요청 인코딩 - SQL 문자열을 바이트 배열로 변환
     */
    fun encodeRequest(sql: String): ByteArray {
        return sql.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * 요청 디코딩 - 바이트 배열을 SQL 문자열로 변환
     */
    fun decodeRequest(bytes: ByteArray): String {
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /**
     * 응답 인코딩 - DbResponse를 JSON 바이트 배열로 변환
     */
    fun encodeResponse(response: DbResponse): ByteArray {
        val jsonString = json.encodeToString(response)
        return jsonString.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * 응답 디코딩 - 바이트 배열을 DbResponse로 변환
     */
    fun decodeResponse(bytes: ByteArray): DbResponse {
        val jsonString = bytes.toString(StandardCharsets.UTF_8)
        return json.decodeFromString(jsonString)
    }

    /**
     * 메시지 쓰기 - 길이 접두사 + 데이터
     */
    fun writeMessage(output: OutputStream, data: ByteArray) {
        val dos = DataOutputStream(output)
        dos.writeInt(data.size)
        dos.write(data)
        dos.flush()
    }

    /**
     * 메시지 읽기 - 길이 접두사 읽고 데이터 읽기
     */
    fun readMessage(input: InputStream): ByteArray {
        val dis = DataInputStream(input)
        val length = dis.readInt()
        val data = ByteArray(length)
        dis.readFully(data)
        return data
    }
}
