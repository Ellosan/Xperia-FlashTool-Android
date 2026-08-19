package dev.flashtool.xperia.s1

/**
 * S1 protocol command identifiers.
 *
 * The S1 protocol is the loader protocol Sony's boot ROM speaks while the phone is in flash mode
 * (volume-down held while plugging the cable in, green LED). It is not publicly documented; the
 * opcodes below are the ones used by Androxyde's Flashtool, which is the de-facto reference
 * implementation. If a device answers a command with an unexpected reply, this table is the first
 * place to look — every opcode the app sends is defined here and nowhere else.
 */
enum class S1Command(val code: Int, val label: String) {
    /** Sent by the boot ROM / older loaders as part of the initial handshake. */
    OLD_LOADER(0x01, "old-loader"),

    /** Ends the flash session cleanly; the loader stops accepting data afterwards. */
    END_SESSION(0x04, "end-session"),

    /** Starts a file transfer: payload is the SIN header, which the loader validates. */
    SEND_HEADER(0x05, "send-header"),

    /** One chunk of image payload for the file opened with [SEND_HEADER]. */
    SEND_DATA(0x06, "send-data"),

    /** Queries loader properties (version, max packet size, device id, …). */
    GET_INFO(0x07, "get-info"),

    /** Opens a TA (Trim Area) partition for reading/writing units. */
    OPEN_TA(0x09, "open-ta"),

    /** Closes the currently open TA partition. */
    CLOSE_TA(0x0A, "close-ta"),

    /** Reboots the phone out of flash mode. */
    REBOOT(0x0B, "reboot"),

    /** Writes one TA unit: payload is unit id + length + value. */
    WRITE_TA(0x0C, "write-ta"),

    /** Reads one TA unit: payload is the unit id. */
    READ_TA(0x0D, "read-ta"),
    ;

    companion object {
        fun fromCode(code: Int): S1Command? = entries.firstOrNull { it.code == code }
        fun describe(code: Int): String = fromCode(code)?.label ?: "0x%02X".format(code)
    }
}

/** Sub-selectors for [S1Command.GET_INFO]. */
object S1Info {
    const val LOADER_VERSION = 0x01
    const val MAX_PACKET_SIZE = 0x02
    const val PHONE_ID = 0x03
    const val PHONE_PROPERTIES = 0x04
    const val ROOT_SEED = 0x05
    const val ULTRA_FAST_MODE = 0x06
}
