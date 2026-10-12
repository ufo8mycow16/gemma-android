package app.gemma.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import gemma.core.GemmaApi
import gemma.core.Notice
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

fun Context.allowed(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * The phone's own speech recognition, used to turn what Glen says into text.
 * Everything here must be called on the main thread (Android requires it).
 */
class Listener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onProblem: (Int) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    val isListening: Boolean get() = recognizer != null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun mine() = recognizer === r
            private fun text(bundle: Bundle?) =
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (mine()) onPartial(text(partialResults))
            }
            override fun onResults(results: Bundle?) {
                if (!mine()) return
                release()
                onFinal(text(results))
            }
            override fun onError(error: Int) {
                if (!mine()) return
                release()
                onProblem(error)
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        )
    }

    /** Glen has finished speaking: give the result for what was heard so far. */
    fun finish() {
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
        }
    }

    fun cancel() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.cancel()
            r.destroy()
        } catch (e: Exception) {
        }
    }

    private fun release() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.destroy()
        } catch (e: Exception) {
        }
    }
}

/** Where the phone is, if Glen has allowed it. Read at the moment of sending; never stored. */
class Whereabouts(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var best: Location? = null

    fun permitted(): Boolean =
        context.allowed(Manifest.permission.ACCESS_FINE_LOCATION) || context.allowed(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun consider(l: Location) {
        val current = best
        if (current == null || l.time > current.time) best = l
    }

    /** Ask the phone for its position. Quick: it uses what the phone already knows and requests one fresh reading. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        if (!permitted()) return
        try {
            for (provider in manager.getProviders(true)) {
                manager.getLastKnownLocation(provider)?.let(::consider)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                    .firstOrNull { manager.isProviderEnabled(it) }
                if (provider != null) {
                    manager.getCurrentLocation(provider, null, context.mainExecutor, Consumer<Location?> { it?.let(::consider) })
                }
            }
        } catch (e: Exception) {
        }
    }

    /** The "client" part of a turn, in the form Vision checks: a device name, the time zone, and the position if shared. */
    fun client(clientId: String, share: Boolean): JsonObject = buildJsonObject {
        put("client_id", clientId)
        put("timezone", TimeZone.getDefault().id)
        putJsonObject("location") {
            val fix = best
            when {
                !share -> put("status", "unavailable")
                !permitted() -> put("status", "denied")
                fix == null -> put("status", "unavailable")
                else -> {
                    put("status", "ok")
                    put("latitude", fix.latitude)
                    put("longitude", fix.longitude)
                    if (fix.hasAccuracy()) put("accuracy", fix.accuracy.toDouble())
                    put("timestamp", fix.time)
                }
            }
        }
    }
}

/** A picture made ready to send: small enough for Vision, and the right way up. */
class Picture(val dataUrl: String, val preview: Bitmap)

object Pictures {
    fun prepare(context: Context, uri: Uri, maxBytes: Long): Picture? = try {
        val bitmap = decode(context, uri, 1600) ?: return null
        var quality = 86
        var bytes = jpeg(bitmap, quality)
        val limit = if (maxBytes > 0) maxBytes else 6_000_000L
        while (bytes.size > limit && quality > 40) {
            quality -= 12
            bytes = jpeg(bitmap, quality)
        }
        if (bytes.size > limit) null
        else Picture("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), scale(bitmap, 240))
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    fun decodeBytes(bytes: ByteArray, longest: Int = 900): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun scale(bitmap: Bitmap, longest: Int): Bitmap {
        val biggest = maxOf(bitmap.width, bitmap.height)
        if (biggest <= longest) return bitmap
        val f = longest.toFloat() / biggest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun decode(context: Context, uri: Uri, longest: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // This route also turns the picture the right way up.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val biggest = maxOf(info.size.width, info.size.height)
                if (biggest > longest) {
                    val f = longest.toFloat() / biggest
                    decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                }
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return decodeBytes(bytes, longest)?.let { scale(it, longest) }
    }
}

/** Records from the microphone into a file in the app's private cache, for Vision to transcribe. */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean = try {
        val target = File(context.cacheDir, "recording.m4a").also { it.delete() }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(48000)
        r.setAudioChannels(1)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = target
        true
    } catch (e: Exception) {
        discard()
        false
    }

    /** Stops and hands back the recording, which is then deleted from the phone. */
    fun finish(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            r.release()
            file?.readBytes()
        } catch (e: Exception) {
            try {
                r.release()
            } catch (ignored: Exception) {
            }
            null
        } finally {
            file?.delete()
            file = null
        }
    }

    fun discard() {
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        file?.delete()
        file = null
    }
}

/** Things Gemma says unprompted (a reminder falling due, a finished follow-up), shown as phone notifications. */
object Notices {
    const val MESSAGES = "gemma_messages"
    const val VOICE = "gemma_voice"
    const val EXTRA_CONVERSATION = "conversation_id"
    private const val WORK = "gemma-notices"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(MESSAGES, "Messages from Gemma", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Reminders and things Gemma tells you unprompted" }
        )
        manager.createNotificationChannel(
            NotificationChannel(VOICE, "Voice conversation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a voice conversation is running" }
        )
    }

    /** Check Vision about every fifteen minutes (the shortest Android allows) while the app is closed. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NoticeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** New notices since the last one this phone has seen. The first time, nothing old is replayed. */
    fun fetchNew(storage: Storage, api: GemmaApi): List<Notice> {
        val seen = storage.lastNoticeId
        if (seen < 0) {
            storage.lastNoticeId = api.notifications(null).latest
            return emptyList()
        }
        val page = api.notifications(seen)
        val newest = maxOf(page.latest, page.items.maxOfOrNull { it.id } ?: seen)
        // If Vision's numbering went backwards (a restored database), start again from where it is now.
        if (newest != seen) storage.lastNoticeId = newest
        return page.items.filter { it.id > seen }
    }

    fun post(context: Context, notice: Notice) {
        if (Build.VERSION.SDK_INT >= 33 && !context.allowed(Manifest.permission.POST_NOTIFICATIONS)) return
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        notice.conversationId?.let { open.putExtra(EXTRA_CONVERSATION, it) }
        val pending = PendingIntent.getActivity(
            context, (notice.id % Int.MAX_VALUE).toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built: Notification = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_gemma)
            .setContentTitle("Gemma")
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((notice.id % Int.MAX_VALUE).toInt(), built)
        } catch (e: SecurityException) {
        }
    }
}

class NoticeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            val storage = Storage(applicationContext)
            val address = storage.serverAddress
            val key = storage.accessKey
            if (address.isNotBlank() && key.isNotBlank() && storage.prefs.notifications) {
                Notices.fetchNew(storage, GemmaApi(address, { key })).forEach { Notices.post(applicationContext, it) }
            }
        } catch (e: Exception) {
            // Vision is off or out of reach: nothing to show, try again next time.
        }
        return Result.success()
    }
}

/**
 * Keeps the app alive while a voice conversation is running with the screen
 * off or another app in front. It does nothing itself: it only holds the
 * notification Android requires for that.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            onEnd?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        Notices.ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getService(
            this, 2, Intent(this, VoiceService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notices.VOICE)
            .setSmallIcon(R.drawable.ic_stat_gemma)
            .setContentTitle("Gemma")
            .setContentText("Voice conversation is on")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "End", end)
            .build()
        try {
            var type = 0
            if (Build.VERSION.SDK_INT >= 29) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= 30 && allowed(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, 7, notification, type)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_END = "app.gemma.android.END_VOICE"
        /** Set by the app so the notification's End button really ends the conversation. */
        @Volatile var onEnd: (() -> Unit)? = null

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}

/** Files between the phone and Gemma, through Android's own pickers and Downloads; no storage permission is needed. */
object Phone {
    fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
        }
        return name to size
    }

    fun readAll(context: Context, uri: Uri, maxBytes: Long): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) return@use null
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun safeName(name: String) = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }.take(120)

    /** Into the public Downloads folder (Android 10+), or the app's own Downloads folder on older phones. */
    fun saveToDownloads(context: Context, filename: String, mime: String, bytes: ByteArray): Uri? = try {
        val name = safeName(filename)
        val type = mime.ifBlank { "application/octet-stream" }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, type)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Gemma")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("no entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("no stream")
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Gemma").apply { mkdirs() }
            val file = java.io.File(dir, name)
            file.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(context, "app.gemma.android.files", file)
        }
    } catch (e: Exception) {
        null
    }

    fun openWith(context: Context, uri: Uri, mime: String): Boolean = try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
