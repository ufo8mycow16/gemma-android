package gemma.core

/** Where the sound is inside a WAV file, and how to play it. */
data class WavInfo(
    val sampleRate: Int, val channels: Int, val bitsPerSample: Int,
    /** 1 = whole-number samples, 3 = floating point. */
    val format: Int, val dataOffset: Int, val dataLength: Int,
) {
    val seconds: Double
        get() = if (sampleRate <= 0 || channels <= 0 || bitsPerSample < 8) 0.0
        else dataLength.toDouble() / (sampleRate.toDouble() * channels * (bitsPerSample / 8))
}

object Wav {
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
    private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

    /** Returns null for anything that is not a playable WAV, rather than guessing. */
    fun parse(b: ByteArray): WavInfo? {
        if (b.size < 44 || tag(b, 0) != "RIFF" || tag(b, 8) != "WAVE") return null
        var at = 12
        var rate = 0; var channels = 0; var bits = 0; var format = 0
        while (at + 8 <= b.size) {
            val name = tag(b, at)
            val size = u32(b, at + 4)
            val body = at + 8
            if (name == "fmt " && size >= 16 && body + 16 <= b.size) {
                format = u16(b, body); channels = u16(b, body + 2); rate = u32(b, body + 4).toInt(); bits = u16(b, body + 14)
                // "Extensible" files keep the real format a little further in.
                if (format == 0xFFFE && size >= 26 && body + 26 <= b.size) format = u16(b, body + 24)
            } else if (name == "data") {
                if (rate <= 0 || channels !in 1..2 || format !in listOf(1, 3)) return null
                if (format == 1 && bits != 16 && bits != 8) return null
                if (format == 3 && bits != 32) return null
                // A stream written without a known length says 0 or 0xFFFFFFFF: use what is actually there.
                val available = b.size - body
                val length = if (size <= 0L || size > available) available else size.toInt()
                val frame = channels * (bits / 8)
                return WavInfo(rate, channels, bits, format, body, length - length % frame)
            }
            if (size < 0 || size > Int.MAX_VALUE) return null
            at = body + size.toInt() + (size.toInt() and 1)
        }
        return null
    }
}
