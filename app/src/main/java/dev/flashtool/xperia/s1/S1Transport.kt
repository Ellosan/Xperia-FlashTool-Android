package dev.flashtool.xperia.s1

import java.io.Closeable
import java.io.IOException

/**
 * Byte pipe to a phone in flash mode. Abstracted so the flashing engine can be driven by a real
 * USB link on a device and by a simulated loader in unit tests.
 */
interface S1Transport : Closeable {
    val description: String

    /** Writes the whole buffer, throwing if the device stops accepting data. */
    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, timeoutMs: Int = DEFAULT_TIMEOUT_MS)

    /**
     * Reads up to [length] bytes into [dest]; returns the number of bytes actually read, which
     * may be 0 if the device had nothing to say within the timeout.
     */
    @Throws(IOException::class)
    fun read(dest: ByteArray, offset: Int = 0, length: Int = dest.size - offset, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
    }
}

class S1IoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The loader answered, but with a failure. [detail] is whatever the loader put in the payload. */
class S1ProtocolException(
    val command: S1Command?,
    val replyCommand: Int,
    val detail: String,
) : IOException(
    "Loader rejected ${command?.label ?: "command"}: " +
        "reply=${S1Command.describe(replyCommand)}${if (detail.isNotEmpty()) " ($detail)" else ""}",
)
