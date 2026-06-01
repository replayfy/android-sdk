package com.replayfy.android.internal.mobile

import java.io.ByteArrayOutputStream

/**
 * Binary wire encoder for the mobile message protocol.
 *
 * Encoding (must match the backend decoder byte-for-byte; verified
 * identical to the iOS encoder):
 *   - uint   → LEB128 varint (low 7 bits per byte, MSB = continuation)
 *   - int    → zigzag, then varint
 *   - string → varint(utf8 byte length) followed by the UTF-8 bytes
 *   - bool   → single byte (1 = true)
 *
 * Each message on the wire is:
 *   [varint type][varint timestamp][varint length][fields…]
 */
enum class MobileMsgType(val id: Long) {
    METADATA(92),
    EVENT(93),
    USER_ID(94),
    USER_ANONYMOUS_ID(95),
    SCREEN_CHANGES(96),
    CRASH(97),
    VIEW_COMPONENT(98),
    CLICK(100),
    INPUT(101),
    PERFORMANCE(102),
    LOG(103),
    INTERNAL_ERROR(104),
    NETWORK_CALL(105),
    SWIPE(106),
    BATCH_META(107),
    GRAPH_QL(109),
}

/** Buffer with the primitive writers. */
class WireBuffer {
    private val out = ByteArrayOutputStream()

    fun writeUInt(value: Long) {
        var v = value
        while (v.toULong() >= 0x80uL) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = (v.toULong() shr 7).toLong()
        }
        out.write((v and 0xff).toInt())
    }

    fun writeInt(value: Long) {
        val uv = (value shl 1) xor (value shr 63)
        writeUInt(uv)
    }

    fun writeString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeUInt(bytes.size.toLong())
        out.write(bytes)
    }

    fun writeBool(b: Boolean) {
        out.write(if (b) 1 else 0)
    }

    fun writeBytes(b: ByteArray) {
        out.write(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Builds complete framed messages. Each is
 * [varint type][varint timestamp][varint bodyLength][body].
 */
object MobileWire {
    fun message(type: MobileMsgType, timestamp: Long, body: ByteArray): ByteArray {
        val buf = WireBuffer()
        buf.writeUInt(type.id)
        buf.writeUInt(timestamp)
        buf.writeUInt(body.size.toLong())
        buf.writeBytes(body)
        return buf.toByteArray()
    }

    private fun body(block: WireBuffer.() -> Unit): ByteArray {
        val b = WireBuffer(); b.block(); return b.toByteArray()
    }

    fun batchMeta(firstIndex: Long, timestamp: Long): ByteArray =
        message(MobileMsgType.BATCH_META, timestamp, body { writeUInt(firstIndex) })

    fun click(label: String, x: Long, y: Long, timestamp: Long): ByteArray =
        message(MobileMsgType.CLICK, timestamp, body { writeString(label); writeUInt(x); writeUInt(y) })

    fun swipe(label: String, x: Long, y: Long, direction: String, timestamp: Long): ByteArray =
        message(MobileMsgType.SWIPE, timestamp, body { writeString(label); writeUInt(x); writeUInt(y); writeString(direction) })

    fun input(value: String, masked: Boolean, label: String, timestamp: Long): ByteArray =
        message(MobileMsgType.INPUT, timestamp, body { writeString(value); writeBool(masked); writeString(label) })

    fun performance(name: String, value: Long, timestamp: Long): ByteArray =
        message(MobileMsgType.PERFORMANCE, timestamp, body { writeString(name); writeUInt(value) })

    fun log(severity: String, content: String, timestamp: Long): ByteArray =
        message(MobileMsgType.LOG, timestamp, body { writeString(severity); writeString(content) })

    fun networkCall(
        type: String, method: String, url: String, request: String,
        response: String, status: Long, duration: Long, timestamp: Long,
    ): ByteArray = message(MobileMsgType.NETWORK_CALL, timestamp, body {
        writeString(type); writeString(method); writeString(url)
        writeString(request); writeString(response); writeUInt(status); writeUInt(duration)
    })

    fun crash(name: String, reason: String, stacktrace: String, timestamp: Long): ByteArray =
        message(MobileMsgType.CRASH, timestamp, body { writeString(name); writeString(reason); writeString(stacktrace) })

    fun viewComponent(screenName: String, viewName: String, visible: Boolean, timestamp: Long): ByteArray =
        message(MobileMsgType.VIEW_COMPONENT, timestamp, body { writeString(screenName); writeString(viewName); writeBool(visible) })

    fun screenChanges(x: Long, y: Long, width: Long, height: Long, timestamp: Long): ByteArray =
        message(MobileMsgType.SCREEN_CHANGES, timestamp, body { writeUInt(x); writeUInt(y); writeUInt(width); writeUInt(height) })

    fun event(name: String, payload: String, timestamp: Long): ByteArray =
        message(MobileMsgType.EVENT, timestamp, body { writeString(name); writeString(payload) })

    fun metadata(key: String, value: String, timestamp: Long): ByteArray =
        message(MobileMsgType.METADATA, timestamp, body { writeString(key); writeString(value) })

    fun userId(id: String, timestamp: Long): ByteArray =
        message(MobileMsgType.USER_ID, timestamp, body { writeString(id) })
}
