package app.gemma.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import gemma.core.SpeechQueue
import gemma.core.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Plays Gemma's voice. Vision makes the audio a piece at a time; this fetches
 * the pieces ahead, plays them in order without gaps, and falls silent the
 * instant it is told to. It plays what Vision sent and nothing else: the phone
 * has no voice of its own.
 */
class Speaker(
    context: Context,
    private val fetch: (String) -> ByteArray,
    private val log: (String) -> Unit,
) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private sealed class Item {
        class Piece(val id: String, val generation: Int, val data: Future<ByteArray?>) : Item()
        class End(val id: String, val generation: Int) : Item()
    }

    private val items = LinkedBlockingQueue<Item>()
    private val downloads = Executors.newFixedThreadPool(2)
    @Volatile private var generation = 0
    @Volatile private var currentId: String? = null
    @Volatile private var track: AudioTrack? = null
    private var trackShape = Triple(0, 0, 0)
    @Volatile private var framesWritten = 0L
    private var hasFocus = false

    /** 1.0 is her natural pace. */
    @Volatile var speed: Float = 1f
    /** Set while the microphone is open in live mode, so the recogniser's own brief sounds do not silence her. */
    @Volatile var keepThroughShortInterruptions = false
    /** Called once per id: `completed` is false if it was cut off by a call or another app. */
    @Volatile var onFinished: ((id: String, completed: Boolean) -> Unit)? = null

    private val _speaking = MutableStateFlow<String?>(null)
    /** The id (turn or played message) whose audio is sounding now, or null when silent. */
    val speaking: StateFlow<String?> = _speaking

    private val order = SpeechQueue { id, _, url ->
        val mine = generation
        items.put(Item.Piece(id, mine, downloads.submit(Callable<ByteArray?> {
            try {
                fetch(url)
            } catch (e: Exception) {
                null
            }
        })))
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val lost = change == AudioManager.AUDIOFOCUS_LOSS ||
            (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && !keepThroughShortInterruptions)
        if (lost) {
            val id = stop()
            if (id != null) onFinished?.invoke(id, false)
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    init {
        thread(name = "gemma-voice", isDaemon = true) { loop() }
    }

    /** Speech for `id` is about to arrive. Anything still playing from before is dropped. */
    @Synchronized fun begin(id: String) {
        if (currentId == id) return
        silence()
        currentId = id
        order.begin(id)
    }

    fun offer(id: String, seq: Int, url: String) {
        if (id == currentId) order.offer(id, seq, url)
    }

    /** The words these pieces were made from were withdrawn: drop what has not been heard yet. */
    @Synchronized fun withdraw(id: String) {
        if (id != currentId) return
        order.reset(id)
        generation++
        items.clear()
        flushTrack()
    }

    /** No more pieces will come for `id`; finish what is queued, then report. */
    fun end(id: String) {
        if (id == currentId) items.put(Item.End(id, generation))
    }

    /** Silence, now. Returns the id that was cut off, if any. */
    @Synchronized fun stop(): String? {
        val id = currentId
        silence()
        if (id != null) order.stop(id)
        return id
    }

    val isActive: Boolean get() = currentId != null

    /** Earphones, a headset or a car: places where the microphone does not hear her. */
    fun privateOutput(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (e: Exception) {
        false
    }

    private fun silence() {
        generation++
        items.clear()
        flushTrack()
        currentId = null
        _speaking.value = null
        if (hasFocus) {
            try {
                audio.abandonAudioFocusRequest(focusRequest)
            } catch (e: Exception) {
            }
            hasFocus = false
        }
    }

    private fun flushTrack() {
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (e: Exception) {
            }
        }
        framesWritten = 0
    }

    private fun loop() {
        while (true) {
            val item = try {
                items.take()
            } catch (e: InterruptedException) {
                return
            }
            try {
                when (item) {
                    is Item.Piece -> if (item.generation == generation) {
                        val bytes = try {
                            item.data.get(90, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            null
                        }
                        if (bytes == null) log("voice: a piece could not be fetched")
                        else if (item.generation == generation) play(bytes, item.generation, item.id)
                    }
                    is Item.End -> if (item.generation == generation) {
                        waitUntilHeard(item.generation)
                        var finished = false
                        synchronized(this) {
                            if (item.generation == generation) {
                                silence()
                                finished = true
                            }
                        }
                        if (finished) onFinished?.invoke(item.id, true)
                    }
                }
            } catch (e: Exception) {
                log("voice: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun play(bytes: ByteArray, mine: Int, id: String) {
        val info = Wav.parse(bytes)
        if (info == null) {
            log("voice: a piece was not playable audio")
            return
        }
        var pcm = bytes
        var offset = info.dataOffset
        var length = info.dataLength
        var encoding = if (info.bitsPerSample == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        var bytesPerSample = info.bitsPerSample / 8
        if (info.format == 3) {
            // Floating-point samples: turn them into the ordinary whole-number kind.
            val count = info.dataLength / 4
            val out = ByteArray(count * 2)
            val reader = ByteBuffer.wrap(bytes, info.dataOffset, count * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val v = (reader.getFloat().coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = (v and 0xff).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
            }
            pcm = out; offset = 0; length = out.size
            encoding = AudioFormat.ENCODING_PCM_16BIT; bytesPerSample = 2
        }
        val t = trackFor(info.sampleRate, info.channels, encoding) ?: return
        if (mine != generation) return
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        try {
            t.playbackParams = t.playbackParams.setSpeed(speed.coerceIn(0.5f, 2f))
        } catch (e: Exception) {
        }
        t.play()
        _speaking.value = id
        var at = offset
        val end = offset + length
        while (at < end && mine == generation) {
            val n = t.write(pcm, at, minOf(8192, end - at))
            if (n <= 0) break
            at += n
        }
        framesWritten += ((at - offset) / (bytesPerSample * info.channels)).toLong()
    }

    private fun trackFor(rate: Int, channels: Int, encoding: Int): AudioTrack? {
        val shape = Triple(rate, channels, encoding)
        track?.let { if (trackShape == shape) return it }
        try {
            track?.release()
        } catch (e: Exception) {
        }
        track = null
        framesWritten = 0
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val smallest = AudioTrack.getMinBufferSize(rate, mask, encoding)
        if (smallest <= 0) {
            log("voice: this phone cannot play $rate Hz audio")
            return null
        }
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(smallest * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { track = it; trackShape = shape }
        } catch (e: Exception) {
            log("voice: the audio player could not start (${e.javaClass.simpleName})")
            null
        }
    }

    /** Let the last of the sound actually come out of the speaker before saying she has finished. */
    private fun waitUntilHeard(mine: Int) {
        val t = track ?: return
        val target = framesWritten
        if (target <= 0) return
        try {
            t.stop()      // in streaming mode this plays out whatever is still buffered
        } catch (e: Exception) {
            return
        }
        var last = -1L
        var still = 0
        while (mine == generation) {
            val head = t.playbackHeadPosition.toLong() and 0xffffffffL
            if (head >= target) break
            if (head == last) {
                still++
                if (still > 60) break
            } else {
                still = 0
                last = head
            }
            Thread.sleep(25)
        }
        framesWritten = 0
    }
}
