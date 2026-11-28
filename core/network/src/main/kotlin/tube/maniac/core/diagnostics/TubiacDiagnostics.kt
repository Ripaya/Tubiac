package tube.maniac.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class LogcatEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
)

@Serializable
data class PendingIssueReport(
    val fingerprint: String,
    val category: String,
    val title: String,
    val detail: String,
    val stacktrace: String = "",
    val logcatExcerpt: String = "",
    val environment: String = "",
    val createdAtEpochMs: Long,
)

data class DiagnosticReporterState(
    val enabled: Boolean = true,
    val pendingCount: Int = 0,
    val sentCount: Int = 0,
    val hasAutoTransport: Boolean = false,
    val lastStatus: String = "Monitoring active",
    val latestPendingIssue: PendingIssueReport? = null,
)

/**
 * In-app circular logcat buffer + rolling disk log with automatic PII/secret redaction
 * and Android system `logcat -d` tail capture for diagnostics & GitHub issue reporting.
 */
object TubiacLogcat {
    private const val MAX_MEMORY_ENTRIES = 400
    private const val MAX_LOG_FILE_BYTES = 262_144L

    private val lock = Any()
    private val ringBuffer = ArrayDeque<LogcatEntry>(MAX_MEMORY_ENTRIES)
    private val _entries = MutableStateFlow<List<LogcatEntry>>(emptyList())
    val entries: StateFlow<List<LogcatEntry>> = _entries.asStateFlow()

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        initForDirectory(context.filesDir)
    }

    fun initForDirectory(filesDir: File) {
        val dir = File(filesDir, "diagnostics").apply { mkdirs() }
        logFile = File(dir, "tubiac.log")
    }

    fun d(tag: String, message: String) = record("D", tag, message, null)
    fun i(tag: String, message: String) = record("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = record("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = record("E", tag, message, throwable)

    fun record(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val sanitizedMsg = redactSensitive(message)
        val stack = throwable?.let { redactSensitive(it.stackTraceToString()).take(2_500) }.orEmpty()
        val combined = if (stack.isNotBlank()) "$sanitizedMsg\n$stack" else sanitizedMsg

        runCatching {
            when (level) {
                "E", "F" -> Log.e(tag, combined)
                "W" -> Log.w(tag, combined)
                "I" -> Log.i(tag, combined)
                else -> Log.d(tag, combined)
            }
        }

        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LogcatEntry(timestamp = ts, level = level, tag = tag, message = combined)

        synchronized(lock) {
            if (ringBuffer.size >= MAX_MEMORY_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
            _entries.value = ringBuffer.toList()

            logFile?.let { file ->
                runCatching {
                    if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                        val kept = file.readLines().takeLast(200).joinToString("\n")
                        file.writeText(kept + "\n")
                    }
                    file.appendText("$ts $level/$tag: $combined\n")
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
            _entries.value = emptyList()
            runCatching { logFile?.writeText("") }
        }
    }

    fun formatInAppLog(maxLines: Int = 150): String = synchronized(lock) {
        ringBuffer.toList()
            .takeLast(maxLines)
            .joinToString("\n") { "${it.timestamp} ${it.level}/${it.tag}: ${it.message}" }
    }

    fun captureSystemAndAppLogcat(maxLines: Int = 140): String {
        val pid = runCatching { Process.myPid() }.getOrNull()
        val cmd = if (pid != null && pid > 0) {
            arrayOf("logcat", "-d", "-t", maxLines.toString(), "-v", "time", "--pid=$pid")
        } else {
            arrayOf("logcat", "-d", "-t", maxLines.toString(), "-v", "time")
        }
        val sysLines = runCatching {
            val proc = Runtime.getRuntime().exec(cmd)
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            redactSensitive(text).trim()
        }.getOrDefault("")

        val appLog = formatInAppLog(maxLines)
        return buildString {
            if (appLog.isNotBlank()) {
                appendLine("=== Tubiac Structured Events ===")
                appendLine(appLog.takeLast(8_000))
            }
            if (sysLines.isNotBlank()) {
                appendLine("=== Android Logcat Tail ===")
                appendLine(sysLines.takeLast(8_000))
            }
        }.trim()
    }

    fun redactSensitive(input: String): String = input
        .replace(Regex("(?i)(Authorization:\\s*Bearer\\s+)[A-Za-z0-9._-]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(access_token=|token=|ghp_|github_pat_)[A-Za-z0-9._-]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(Cookie:\\s*)[^\\r\\n]+"), "$1[REDACTED]")
}

/**
 * Deduplicating crash, glitch, and extension-error reporter that captures Logcat traces
 * automatically (when opted in) and submits issues to Ripaya/Tubiac via relay or GitHub token.
 */
object AutoIssueReporter {
    const val REPO_OWNER = "Ripaya"
    const val REPO_NAME = "Tubiac"
    const val GITHUB_ISSUES_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/issues"
    const val GITHUB_SEARCH_ISSUES_API = "https://api.github.com/search/issues"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val enabledFlag = AtomicBoolean(true)
    private val relayUrlRef = AtomicReference("")
    private val githubTokenRef = AtomicReference("")
    private val handlerInstalled = AtomicBoolean(false)
    private var appVersionLabel = "0.1.0-alpha (1)"
    private var packageNameLabel = "tube.maniac"
    private var diagnosticsDir: File? = null
    private var onQueueCallback: (() -> Unit)? = null

    private val _state = MutableStateFlow(DiagnosticReporterState())
    val state: StateFlow<DiagnosticReporterState> = _state.asStateFlow()

    fun initialize(
        context: Context,
        versionName: String,
        versionCode: Long,
        defaultRelayUrl: String = "",
        defaultGitHubToken: String = "",
        onPendingQueued: (() -> Unit)? = null,
    ) {
        initializeForDirectory(
            filesDir = context.filesDir,
            packageName = context.packageName,
            versionName = versionName,
            versionCode = versionCode,
            defaultRelayUrl = defaultRelayUrl,
            defaultGitHubToken = defaultGitHubToken,
            onPendingQueued = onPendingQueued,
        )
    }

    fun initializeForDirectory(
        filesDir: File,
        packageName: String = "tube.maniac",
        versionName: String,
        versionCode: Long,
        defaultRelayUrl: String = "",
        defaultGitHubToken: String = "",
        onPendingQueued: (() -> Unit)? = null,
    ) {
        TubiacLogcat.initForDirectory(filesDir)
        val root = File(filesDir, "diagnostics").apply { mkdirs() }
        File(root, "pending").mkdirs()
        diagnosticsDir = root
        appVersionLabel = "v$versionName (Build $versionCode)"
        packageNameLabel = packageName
        if (defaultRelayUrl.isNotBlank()) relayUrlRef.set(defaultRelayUrl.trim())
        if (defaultGitHubToken.isNotBlank()) githubTokenRef.set(defaultGitHubToken.trim())
        onQueueCallback = onPendingQueued
        installUncaughtExceptionHandler()
        refreshStateSnapshot()
    }

    fun updatePreferences(enabled: Boolean, customRelayUrl: String = "") {
        enabledFlag.set(enabled)
        if (customRelayUrl.isNotBlank()) {
            relayUrlRef.set(customRelayUrl.trim())
        }
        refreshStateSnapshot()
    }

    private fun installUncaughtExceptionHandler() {
        if (!handlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                TubiacLogcat.e("UncaughtCrash", "Fatal crash on thread ${thread.name}", throwable)
                if (enabledFlag.get()) {
                    queueIssueReport(
                        category = "crash",
                        title = "[Crash] ${throwable.javaClass.simpleName}: ${throwable.message?.take(90) ?: "Fatal exception"}",
                        detail = "Uncaught exception on thread `${thread.name}`.",
                        throwable = throwable,
                    )
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun reportRuntimeBug(
        category: String,
        title: String,
        detail: String,
        throwable: Throwable? = null,
    ) {
        TubiacLogcat.e("AutoReporter/$category", "$title — $detail", throwable)
        if (!enabledFlag.get()) {
            refreshStateSnapshot("Automatic reporting is turned off by user")
            return
        }
        val queued = queueIssueReport(category, title, detail, throwable)
        if (queued) {
            onQueueCallback?.invoke()
        }
    }

    @Synchronized
    private fun queueIssueReport(
        category: String,
        title: String,
        detail: String,
        throwable: Throwable?,
    ): Boolean {
        val dir = diagnosticsDir ?: return false
        val rootCause = throwable?.unwrapRoot()
        val stack = rootCause?.let { TubiacLogcat.redactSensitive(it.stackTraceToString()).take(4_500) }.orEmpty()
        val topFrame = rootCause?.stackTrace?.firstOrNull()?.let { "${it.className}:${it.lineNumber}" }.orEmpty()
        val fpInput = "$category|${rootCause?.javaClass?.name ?: title}|$topFrame"
        val fingerprint = sha256Short(fpInput)

        val sentFile = File(dir, "sent_fingerprints.txt")
        if (sentFile.exists() && sentFile.readLines().any { it == fingerprint }) {
            refreshStateSnapshot()
            return false
        }

        val pendingDir = File(dir, "pending").apply { mkdirs() }
        val target = File(pendingDir, "$fingerprint.json")
        if (target.exists()) {
            refreshStateSnapshot()
            return false
        }

        val report = PendingIssueReport(
            fingerprint = fingerprint,
            category = category,
            title = title.take(120),
            detail = TubiacLogcat.redactSensitive(detail),
            stacktrace = stack,
            logcatExcerpt = TubiacLogcat.captureSystemAndAppLogcat(120),
            environment = "$appVersionLabel • $packageNameLabel • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}",
            createdAtEpochMs = System.currentTimeMillis(),
        )
        runCatching {
            val tmp = File(pendingDir, "$fingerprint.json.tmp")
            tmp.writeText(json.encodeToString(report))
            tmp.renameTo(target)
        }
        refreshStateSnapshot("Queued issue [$fingerprint] for upload")
        return true
    }

    suspend fun flushPendingReports(okHttpClient: OkHttpClient): Int = withContext(Dispatchers.IO) {
        if (!enabledFlag.get()) {
            refreshStateSnapshot("Automatic reporting paused (turned off)")
            return@withContext 0
        }
        val dir = diagnosticsDir ?: return@withContext 0
        val pendingDir = File(dir, "pending")
        val pendingFiles = pendingDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (pendingFiles.isEmpty()) {
            refreshStateSnapshot("No pending bug reports")
            return@withContext 0
        }

        val relayUrl = relayUrlRef.get().trim()
        val ghToken = githubTokenRef.get().trim()
        if (relayUrl.isBlank() && ghToken.isBlank()) {
            refreshStateSnapshot("${pendingFiles.size} report(s) captured locally (no relay/token configured)")
            return@withContext 0
        }

        var sentNow = 0
        for (file in pendingFiles) {
            val report = runCatching { json.decodeFromString<PendingIssueReport>(file.readText()) }.getOrNull()
                ?: run {
                    file.delete()
                    continue
                }
            val uploaded = runCatching {
                when {
                    relayUrl.isNotBlank() -> postToRelay(okHttpClient, relayUrl, report)
                    else -> postToGitHubIssues(okHttpClient, ghToken, report)
                }
            }.getOrElse { err ->
                TubiacLogcat.w("AutoReporter", "Upload failed for ${report.fingerprint}: ${err.message}")
                refreshStateSnapshot("Upload deferred: ${err.message}")
                false
            }
            if (uploaded) {
                markSent(dir, report.fingerprint)
                file.delete()
                sentNow++
            }
        }
        refreshStateSnapshot(
            if (sentNow > 0) "Uploaded $sentNow issue report(s) to $REPO_OWNER/$REPO_NAME"
            else _state.value.lastStatus,
        )
        sentNow
    }

    private fun postToRelay(okHttpClient: OkHttpClient, relayUrl: String, report: PendingIssueReport): Boolean {
        val payload = buildJsonObject {
            put("event_type", "tubiac-bug-report")
            put("repo", "$REPO_OWNER/$REPO_NAME")
            put("title", report.title)
            put("body", formatIssueMarkdown(report))
            put("labels", JsonArray(listOf(JsonPrimitive("bug"), JsonPrimitive("auto-reported"), JsonPrimitive(report.category))))
            put("fingerprint", report.fingerprint)
            put("category", report.category)
            put("environment", report.environment)
        }
        val req = Request.Builder()
            .url(relayUrl)
            .post(json.encodeToString(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .build()
        return okHttpClient.newCall(req).execute().use { it.isSuccessful }
    }

    private fun postToGitHubIssues(okHttpClient: OkHttpClient, token: String, report: PendingIssueReport): Boolean {
        val marker = "<!-- tubiac-fp:${report.fingerprint} -->"
        val query = URLEncoder.encode("repo:$REPO_OWNER/$REPO_NAME is:issue is:open \"$marker\"", "UTF-8")
        val searchReq = Request.Builder()
            .url("$GITHUB_SEARCH_ISSUES_API?q=$query")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()

        val existingIssueNumber = runCatching {
            okHttpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body.string()).jsonObject
                root["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("number")?.jsonPrimitive?.intOrNull
            }
        }.getOrNull()

        if (existingIssueNumber != null) {
            val commentPayload = buildJsonObject {
                put("body", "### Re-occurrence reported automatically\n**Environment:** ${report.environment}\n\n${report.detail}")
            }
            val commentReq = Request.Builder()
                .url("$GITHUB_ISSUES_API/$existingIssueNumber/comments")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(json.encodeToString(commentPayload).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            return okHttpClient.newCall(commentReq).execute().use { it.isSuccessful }
        }

        val issuePayload = buildJsonObject {
            put("title", report.title)
            put("body", formatIssueMarkdown(report))
            put("labels", JsonArray(listOf(JsonPrimitive("bug"), JsonPrimitive("auto-reported"))))
        }
        val createReq = Request.Builder()
            .url(GITHUB_ISSUES_API)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(json.encodeToString(issuePayload).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return okHttpClient.newCall(createReq).execute().use { it.isSuccessful }
    }

    fun formatIssueMarkdown(report: PendingIssueReport): String = buildString {
        appendLine("### Automated Bug / Logcat Report")
        appendLine("- **Category:** `${report.category}`")
        appendLine("- **Environment:** ${report.environment}")
        appendLine("- **Fingerprint:** `${report.fingerprint}`")
        appendLine()
        appendLine("### Summary")
        appendLine(report.detail)
        if (report.stacktrace.isNotBlank()) {
            appendLine()
            appendLine("### Stacktrace")
            appendLine("```")
            appendLine(report.stacktrace)
            appendLine("```")
        }
        if (report.logcatExcerpt.isNotBlank()) {
            appendLine()
            appendLine("<details><summary>Logcat Excerpt</summary>")
            appendLine()
            appendLine("```text")
            appendLine(report.logcatExcerpt.takeLast(6_000))
            appendLine("```")
            appendLine("</details>")
        }
        appendLine()
        appendLine("<!-- tubiac-fp:${report.fingerprint} -->")
    }

    private fun markSent(dir: File, fingerprint: String) {
        val sentFile = File(dir, "sent_fingerprints.txt")
        runCatching { sentFile.appendText("$fingerprint\n") }
    }

    fun clearPendingAndHistory() {
        val dir = diagnosticsDir ?: return
        runCatching {
            File(dir, "pending").listFiles()?.forEach { it.delete() }
            File(dir, "sent_fingerprints.txt").delete()
        }
        refreshStateSnapshot("Cleared local diagnostic queue")
    }

    private fun refreshStateSnapshot(customStatus: String? = null) {
        val dir = diagnosticsDir
        val pendingFiles = dir?.let { File(it, "pending").listFiles { f -> f.isFile && f.extension == "json" } }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val latest = pendingFiles.firstOrNull()?.let { f ->
            runCatching { json.decodeFromString<PendingIssueReport>(f.readText()) }.getOrNull()
        }
        val sentCount = dir?.let {
            val sf = File(it, "sent_fingerprints.txt")
            if (sf.exists()) sf.readLines().count { l -> l.isNotBlank() } else 0
        } ?: 0
        val hasTransport = relayUrlRef.get().isNotBlank() || githubTokenRef.get().isNotBlank()
        _state.value = DiagnosticReporterState(
            enabled = enabledFlag.get(),
            pendingCount = pendingFiles.size,
            sentCount = sentCount,
            hasAutoTransport = hasTransport,
            lastStatus = customStatus ?: _state.value.lastStatus,
            latestPendingIssue = latest,
        )
    }

    private fun sha256Short(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
}

/** Unwraps RxJava / reflection wrapper exceptions to expose underlying domain or ABI errors. */
fun Throwable.unwrapRoot(): Throwable {
    var cur: Throwable = this
    var depth = 0
    while (cur.cause != null && cur.cause !== cur && depth < 8) {
        if (cur.javaClass.name.startsWith("rx.exceptions.") ||
            cur.javaClass == java.lang.RuntimeException::class.java ||
            cur.javaClass == java.lang.reflect.InvocationTargetException::class.java
        ) {
            cur = cur.cause ?: break
            depth++
        } else {
            break
        }
    }
    return cur
}
