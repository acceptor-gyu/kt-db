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

    fun encodeRequest(request: DbRequest): ByteArray {
        val jsonString = json.encodeToString(request)
        return jsonString.toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeRequest(bytes: ByteArray): DbRequest {
        val jsonString = bytes.toString(StandardCharsets.UTF_8)
        return json.decodeFromString(jsonString)
    }

    fun encodeResponse(response: DbResponse): ByteArray {
        val jsonString = json.encodeToString(response)
        return jsonString.toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeResponse(bytes: ByteArray): DbResponse {
        val jsonString = bytes.toString(StandardCharsets.UTF_8)
        return json.decodeFromString(jsonString)
    }

    fun writeMessage(output: OutputStream, data: ByteArray) {
        val dos = DataOutputStream(output)
        dos.writeInt(data.size)
        dos.write(data)
        dos.flush()
    }

    fun readMessage(input: InputStream): ByteArray {
        val dis = DataInputStream(input)
        val length = dis.readInt()
        val data = ByteArray(length)
        dis.readFully(data)
        return data
    }
}
