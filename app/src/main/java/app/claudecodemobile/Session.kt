package app.claudecodemobile

import android.app.Application
import android.net.Uri
import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.GZIPInputStream

/** One rendered row in the transcript. */
sealed interface Turn {
    data class User(val text: String) : Turn
    data class Assistant(val text: String) : Turn
    data class Tool(
        val id: String,
        val name: String,
        val headline: String,
        val body: String?,
        var result: String? = null,
        var failed: Boolean = false,
        /** Lines on disk so far — watched live while a file is being written. */
        var lines: Int? = null,
        /** True once the tool has reported back. */
        var finished: Boolean = false
    ) : Turn
    data class Thinking(val text: String) : Turn
    data class Notice(val text: String, val isError: Boolean = false) : Turn
    data class Link(val url: String, val label: String) : Turn
    data class Done(val summary: String) : Turn
}

data class Install(val label: String, val present: Boolean, val detail: String)

/** One entry in the model picker: the exact id passed to --model, plus its readable name. */
data class ModelInfo(val id: String, val displayName: String)

/** A stored conversation. [id] is the CLI's own session id, which --resume takes. */
data class SessionRecord(
    val id: String,
    val title: String,
    val updated: Long,
    val model: String,
    val turns: List<Pair<String, String>>
)

private const val BUNDLE_VERSION = 1

class Session(app: Application) : AndroidViewModel(app) {

    val sandbox = Sandbox(app)
    private val engine = Engine(sandbox, viewModelScope)

    val transcript = mutableStateListOf<Turn>()
    val terminal = mutableStateListOf<String>()

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("idle")
        private set
    var setupLog by mutableStateOf("")
        private set
    var installs by mutableStateOf(listOf<Install>())
        private set
    var cwd by mutableStateOf("")
        private set

    /** Declared before init{} on purpose — loadHistory() writes to it during construction. */
    var history by mutableStateOf(listOf<SessionRecord>())
        private set

    private var currentId: String? = null
    private val historyFile get() = File(sandbox.root, "sessions.json")

    init {
        sandbox.ensureLayout()
        sandbox.seedWorkspace()
        // Written every launch, not just on first bootstrap: an app that was already installed
        // has the stamp file, so anything added only inside bootstrap() never reaches it.
        runCatching { sandbox.writeHelpers() }
        cwd = sandbox.workspace.absolutePath
        refreshInstalls()
        loadHistory()
        bootstrap(force = false)
        log("android paths: " + sandbox.visibleAndroidPaths()
            .joinToString(" ") { "${it.first}=${if (it.second) "ok" else "hidden"}" })
        if (sandbox.hasClaude) {
            verifyEngine()
            refreshModels()
            // fakeroot is the thing that lets the agent install packages by itself; without it
            // every apk it runs dies on chown. Put it in place before the first message.
            if (!sandbox.hasFakeroot) installTools() else probeSandbox()
        }
    }

    /**
     * Runs `claude --version` inside the sandbox. This is the check that tells us the binary,
     * proot, and the musl rootfs actually agree with each other — everything else is inference.
     */
    fun verifyEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!sandbox.engineReady) { log("engine not ready: skipping self-test"); return@launch }
            val out = StringBuilder()
            val code = runCatching {
                val p = sandbox.spawn("/usr/local/bin/claude --version 2>&1")
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    .forEachLine { out.appendLine(it.trim()) }
                p.waitFor()
            }.getOrElse { out.appendLine(it.message); -1 }
            log("self-test exit=$code")
            out.toString().trim().lines().take(8).forEach { if (it.isNotBlank()) log("  $it") }
        }
    }

    /**
     * Unpacks the runtime that ships inside the APK: proot with its loader and libraries, and a
     * musl rootfs that supplies /lib/ld-musl-aarch64.so.1. Claude Code itself is deliberately not
     * bundled — it's Anthropic's binary, fetched on demand from npm by the setup button.
     */
    fun bootstrap(force: Boolean) {
        val stamp = File(sandbox.root, ".bootstrap-v$BUNDLE_VERSION")
        if (stamp.exists() && !force) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val assets = getApplication<Application>().assets
                sandbox.ensureLayout()
                log("installing bundled runtime…")

                val files = listOf(
                    Triple("proot", sandbox.bin, true),
                    Triple("loader", sandbox.libexec, true),
                    Triple("loader32", sandbox.libexec, true),
                    Triple("libtalloc.so.2", sandbox.lib, false),
                    Triple("libandroid-shmem.so", sandbox.lib, false)
                )
                for ((name, dir, exec) in files) {
                    val target = File(dir, name)
                    assets.open(name).use { input ->
                        target.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                    }
                    target.setReadable(true, false)
                    if (exec) target.setExecutable(true, false)
                    log("  ${dir.name}/$name  ${target.length()} bytes")
                }

                if (force || !sandbox.hasMuslLoader) {
                    log("unpacking musl rootfs…")
                    assets.open("rootfs.tgz").use { raw ->
                        GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                            val n = extractTar(gz, into = sandbox.rootfs)
                            log("  $n entries")
                        }
                    }
                }
                sandbox.ensureMountPoints()
                sandbox.writeHelpers()
                stamp.writeText(BUNDLE_VERSION.toString())
                log(
                    if (sandbox.hasProot && sandbox.hasMuslLoader)
                        "runtime ready — only Claude Code is missing"
                    else "runtime incomplete, run diagnose"
                )
            }.onFailure {
                android.util.Log.e("claudecode", "bootstrap failed", it)
                log("bootstrap failed: ${it::class.simpleName}: ${it.message}")
            }
            log(
                "state: proot=${sandbox.hasProot} loader=${sandbox.hasLoader} " +
                    "talloc=${sandbox.hasTalloc} musl=${sandbox.hasMuslLoader} claude=${sandbox.hasClaude}"
            )
            refreshInstalls()
        }
    }

    fun refreshInstalls() {
        installs = listOf(
            Install(
                "Claude Code (arm64 musl)",
                sandbox.hasClaude,
                sandbox.tool("claude")?.let { "%.0f MB".format(it.length() / 1048576.0) }
                    ?: "not installed"
            ),
            Install(
                "proot + loader + libs",
                sandbox.hasProot && sandbox.hasLoader && sandbox.hasTalloc,
                if (sandbox.hasProot && sandbox.hasLoader && sandbox.hasTalloc) "bundled, installed"
                else "bundled — press reinstall runtime"
            ),
            Install(
                "musl rootfs (Alpine)",
                sandbox.hasMuslLoader,
                if (sandbox.hasMuslLoader) "/lib/ld-musl-aarch64.so.1 present"
                else "bundled — press reinstall runtime"
            ),
            Install(
                "POSIX shell (bash + tools)",
                sandbox.hasBash,
                if (sandbox.hasBash) "/bin/bash" else "press install shell + tools"
            )
        )
    }

    // ---------------- chat ----------------

    fun send(userText: String) {
        if (userText.isBlank() && attachments.isEmpty()) return
        transcript.add(Turn.User(userText))

        // Attached files live in the workspace; name them so the agent knows they're there and
        // what it may do with them.
        val text = if (attachments.isEmpty()) userText else buildString {
            append("Files are attached in the working directory: ")
            append(attachments.joinToString(", ") { "/workspace/${it.name}" })
            append(". You can read, unzip, edit or run them as needed.\n\n")
            append(userText)
        }.also { attachments = emptyList() }

        // A login is a conversation of its own: while one is running, whatever you type is typed
        // into the CLI's terminal rather than sent to the model.
        if (loginActive && !text.startsWith("/")) {
            submitCode(text)
            return
        }
        if (text.trim().startsWith("/")) {
            if (slashCommand(text.trim())) return
        }

        if (!sandbox.engineReady) {
            transcript.add(
                Turn.Notice(
                    "The engine isn't installed yet — open Setup. The sandbox and terminal work now.",
                    isError = true
                )
            )
            return
        }
        busy = true
        status = "thinking"
        // The CLI insists on a POSIX shell, and Alpine's minirootfs has only busybox ash. Pull
        // bash in on the first message rather than making it a setup chore.
        if (!sandbox.hasBash || !sandbox.hasFakeroot) {
            transcript.add(Turn.Notice("setting up the sandbox toolchain — first run only"))
            // Held so the stop button can cancel it: on the very first message the engine hasn't
            // started yet, so stopping the engine alone would leave this pending send to fire.
            pendingSend = viewModelScope.launch {
                installTools().join()
                // stop button may have cancelled us while the toolchain was installing
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!engine.running) engine.start(::onEvent)
                engine.send(text)
            }
            return
        }
        if (!engine.running) engine.start(::onEvent)
        engine.send(text)
    }

    // ---------------- session history ----------------
    // (state for this lives at the top of the class: Kotlin runs property initializers in
    //  declaration order, so anything init{} touches has to be declared before it)

    private fun loadHistory() {
        history = runCatching {
            if (!historyFile.exists()) return@runCatching emptyList()
            val arr = JSONObject(historyFile.readText()).getJSONArray("sessions")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val turns = o.optJSONArray("turns")
                    SessionRecord(
                        o.optString("id"),
                        o.optString("title"),
                        o.optLong("updated"),
                        o.optString("model"),
                        (0 until (turns?.length() ?: 0)).mapNotNull { k ->
                            turns?.optJSONObject(k)?.let { t -> t.optString("k") to t.optString("x") }
                        }
                    )
                }
            }.sortedByDescending { it.updated }
        }.getOrDefault(emptyList())
    }

    /** Forgets a stored conversation. The CLI keeps its own copy; this drops ours. */
    fun deleteSession(record: SessionRecord) {
        history = history.filterNot { it.id == record.id }
        persistHistory()
        if (currentId == record.id) newSession()
    }

    fun clearHistory() {
        history = emptyList()
        persistHistory()
        newSession()
    }

    private fun persistHistory() {
        runCatching {
            val arr = org.json.JSONArray()
            history.forEach { r ->
                val turnArr = org.json.JSONArray()
                r.turns.forEach { (k, x) -> turnArr.put(JSONObject().put("k", k).put("x", x)) }
                arr.put(
                    JSONObject().put("id", r.id).put("title", r.title)
                        .put("updated", r.updated).put("model", r.model).put("turns", turnArr)
                )
            }
            historyFile.writeText(JSONObject().put("sessions", arr).toString())
        }
    }

    /** Snapshots the visible conversation so it can be reopened later. */
    private fun saveCurrent() {
        val id = currentId ?: return
        if (transcript.none { it is Turn.User }) return
        val title = transcript.filterIsInstance<Turn.User>().firstOrNull()?.text
            ?.lines()?.firstOrNull()?.take(60) ?: "session"
        val turns = transcript.mapNotNull { t ->
            when (t) {
                is Turn.User -> "u" to t.text
                is Turn.Assistant -> "a" to t.text
                is Turn.Tool -> "t" to "${t.name} ${t.headline}"
                else -> null
            }
        }
        val record = SessionRecord(id, title, System.currentTimeMillis(), model, turns)
        history = (listOf(record) + history.filterNot { it.id == id }).take(50)
        persistHistory()
    }

    /**
     * Drops the conversation and the CLI process behind it. The next message starts a fresh
     * session with empty context — the sandbox and everything in the workspace is untouched.
     */
    fun newSession() {
        if (loginActive) cancelLogin()
        saveCurrent()
        engine.stop()
        engine.resumeId = null
        currentId = null
        transcript.clear()
        busy = false
        status = "idle"
    }

    /** Reopens a stored conversation: the transcript is redrawn and the CLI resumes that id. */
    fun openSession(record: SessionRecord) {
        saveCurrent()
        engine.stop()
        transcript.clear()
        record.turns.forEach { (kind, text) ->
            transcript.add(
                when (kind) {
                    "u" -> Turn.User(text)
                    "a" -> Turn.Assistant(text)
                    else -> Turn.Notice(text)
                }
            )
        }
        currentId = record.id
        engine.resumeId = record.id
        if (record.model.isNotBlank()) { model = record.model; engine.model = record.model }
        busy = false
        status = "resumed"
        transcript.add(Turn.Notice("resumed session ${record.id.take(8)}"))
    }

    private var pendingSend: kotlinx.coroutines.Job? = null

    fun interrupt() {
        if (loginActive) { cancelLogin(); transcript.add(Turn.Notice("login cancelled")); return }
        pendingSend?.cancel()          // a first message still waiting on the toolchain install
        pendingSend = null
        engine.stop()
        busy = false
        status = "stopped"
        transcript.add(Turn.Notice("Interrupted"))
    }

    /** Commands the app answers itself, before anything reaches the model. Returns true if handled. */
    private fun slashCommand(text: String): Boolean {
        val parts = text.split(" ")
        return when (parts[0]) {
            "/login" -> {
                login(console = parts.getOrNull(1) == "--console"); true
            }
            "/logout" -> {
                runShellIntoTranscript("/usr/local/bin/claude auth logout"); true
            }
            "/status" -> {
                runShellIntoTranscript("/usr/local/bin/claude auth status"); true
            }
            "/help" -> {
                transcript.add(
                    Turn.Notice(
                        "/login — sign in to your Anthropic account\n" +
                            "/logout — sign out\n" +
                            "/status — show who's signed in\n" +
                            "anything else goes to Claude Code"
                    )
                )
                true
            }
            else -> false
        }
    }

    private fun runShellIntoTranscript(inner: String) {
        if (!sandbox.engineReady) {
            transcript.add(Turn.Notice("engine not installed yet", isError = true)); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val p = sandbox.spawn("$inner 2>&1")
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream)).forEachLine { line ->
                    if (line.isNotBlank()) viewModelScope.launch(Dispatchers.Main) {
                        transcript.add(Turn.Notice(line))
                    }
                }
                p.waitFor()
            }.onFailure {
                viewModelScope.launch(Dispatchers.Main) {
                    transcript.add(Turn.Notice("failed: ${it.message}", isError = true))
                }
            }
            checkAuth()
        }
    }

    private fun onEvent(event: Event) = viewModelScope.launch(Dispatchers.Main) {
        when (event) {
            is Event.Init -> {
                cwd = event.cwd ?: cwd
                status = event.model ?: "ready"
                event.sessionId?.takeIf { it.isNotBlank() }?.let { currentId = it }
                transcript.add(Turn.Notice("session started · ${event.tools.size} tools"))
            }

            is Event.Message -> transcript.add(Turn.Assistant(event.text))

            is Event.Thinking -> {
                status = "thinking"
                // Consecutive thinking blocks are one stretch of reasoning, not several.
                val last = transcript.lastOrNull()
                if (last is Turn.Thinking && event.text.isBlank()) return@launch
                if (last is Turn.Thinking && last.text.isNotBlank()) {
                    transcript[transcript.lastIndex] = Turn.Thinking(last.text + "\n" + event.text)
                } else {
                    transcript.add(Turn.Thinking(event.text))
                }
            }

            is Event.ToolCall -> {
                // The tool call already carries the content being written, so the target line
                // count is known before the file exists — no need to wait for the disk.
                val target = when (event.name) {
                    "Write" -> event.input.optString("content").takeIf { it.isNotEmpty() }?.lines()?.size
                    "Edit" -> event.input.optString("new_string").takeIf { it.isNotEmpty() }?.lines()?.size
                    else -> null
                }
                transcript.add(
                    Turn.Tool(
                        event.id, event.name,
                        headlineFor(event.name, event.input),
                        bodyFor(event.name, event.input),
                        lines = target
                    )
                )
                // Follow whatever file this tool is producing so the row reports progress rather
                // than sitting silent until it returns. Not just Write: the agent often creates
                // files with a Bash heredoc or a redirect instead.
                val path = event.input.optString("file_path")
                    .ifBlank { outputPathOf(event.name, event.input) }
                android.util.Log.i("claudecode", "tool ${event.name} -> ${path.ifBlank { "(no file)" }}")
                if (path.isNotBlank()) watchFile(event.id, path)
            }

            is Event.ToolResult -> {
                // The transcript can be cleared underneath us by "+ new" while a turn streams,
                // so resolve the row and its index together and bail if it's gone.
                val row = transcript.filterIsInstance<Turn.Tool>().lastOrNull { it.id == event.id }
                val idx = if (row == null) -1 else transcript.indexOfLast { it === row }
                if (row != null && idx in transcript.indices) {
                    runCatching {
                        transcript[idx] = row.copy(
                            // keep enough that expanding is worth it; the row shows one line collapsed
                            result = event.content.lines().take(200).joinToString("\n"),
                            failed = event.isError,
                            lines = row.lines ?: countLines(row),
                            finished = true
                        )
                    }
                }
            }

            is Event.Done -> {
                busy = false
                status = "idle"
                saveCurrent()   // a completed turn is worth keeping
                val cost = event.costUsd?.let { "$%.4f".format(it) }
                val secs = event.durationMs?.let { "%.1fs".format(it / 1000.0) }
                transcript.add(Turn.Done(listOfNotNull(event.subtype, secs, cost).joinToString(" · ")))
            }

            is Event.Notice -> {
                if (event.isError) busy = false
                transcript.add(Turn.Notice(event.text, event.isError))
            }
        }
    }

    /**
     * A Bash command that creates a file names it in a redirect or a heredoc target, so the same
     * live progress can be shown for `cat > x`, `foo >> y`, and `tee z`.
     */
    private fun outputPathOf(name: String, input: JSONObject): String {
        if (name != "Bash") return ""
        val cmd = input.optString("command")
        return Regex("""(?:>>?|\btee\s+(?:-a\s+)?)\s*["']?([\w./\-]+)""")
            .findAll(cmd)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .lastOrNull { !it.startsWith("/dev/") }
            .orEmpty()
    }

    /**
     * Polls the file a Write/Edit is producing and reports its growing line count, until the tool
     * reports back. The CLI sends the whole write as one event, so this is the only way to show
     * progress rather than a frozen row.
     */
    private fun watchFile(toolId: String, path: String) {
        if (path.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                val row = transcript.filterIsInstance<Turn.Tool>().lastOrNull { it.id == toolId }
                if (row == null || row.finished) return@launch
                val file = resolveWorkspaceFile(path)
                val n = file?.let { f ->
                    runCatching { f.bufferedReader().useLines { it.count() } }.getOrNull()
                }
                if (n != null && n != row.lines) {
                    withContext(Dispatchers.Main) {
                        val i = transcript.indexOfLast { it === row }
                        if (i in transcript.indices) transcript[i] = row.copy(lines = n)
                    }
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    /** Final count once the tool is done: prefer the file, fall back to the content we were given. */
    private fun countLines(row: Turn.Tool): Int? {
        val fromBody = row.body?.lines()?.size
        val file = resolveWorkspaceFile(row.headline)
        return file?.let { f -> runCatching { f.bufferedReader().useLines { it.count() } }.getOrNull() }
            ?: fromBody
    }

    /** Claude Code renders each tool call as one dense line — mirror that. */
    private fun headlineFor(name: String, input: JSONObject): String = when (name) {
        "Read" -> input.optString("file_path").substringAfterLast('/')
        "Write" -> input.optString("file_path").substringAfterLast('/')
        "Edit" -> input.optString("file_path").substringAfterLast('/')
        "Bash" -> input.optString("command").lines().first().take(80)
        "Glob", "Grep" -> input.optString("pattern")
        "Task" -> input.optString("description")
        else -> input.keys().asSequence().firstOrNull()?.let { input.optString(it).take(60) } ?: ""
    }

    private fun bodyFor(name: String, input: JSONObject): String? = when (name) {
        "Write" -> input.optString("content").lines().take(20).joinToString("\n")
        "Edit" -> buildString {
            input.optString("old_string").lines().take(8).forEach { appendLine("- $it") }
            input.optString("new_string").lines().take(8).forEach { appendLine("+ $it") }
        }.trimEnd().ifBlank { null }
        "Bash" -> input.optString("description").ifBlank { null }
        else -> null
    }

    // ---------------- attachments ----------------

    var attachments by mutableStateOf(listOf<File>())
        private set

    /**
     * Copies a picked file into the workspace so the agent can actually reach it — app-private
     * storage is invisible to the sandbox otherwise, and a content:// uri means nothing to it.
     */
    fun attachFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val name = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: "attachment-${System.currentTimeMillis()}"

                sandbox.ensureLayout()
                var target = File(sandbox.workspace, name)
                var n = 1
                while (target.exists()) {          // never clobber something already there
                    val base = name.substringBeforeLast('.', name)
                    val ext = name.substringAfterLast('.', "")
                    target = File(sandbox.workspace, base + "-" + n++ + if (ext.isEmpty()) "" else ".$ext")
                }
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
                withContext(Dispatchers.Main) {
                    attachments = attachments + target
                    transcript.add(
                        Turn.Notice("added ${target.name} (${target.length() / 1024 + 1} KB) to /workspace")
                    )
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    transcript.add(Turn.Notice("attach failed: ${it.message}", isError = true))
                }
            }
        }
    }

    fun removeAttachment(file: File) { attachments = attachments - file }

    // ---------------- workspace files ----------------

    /**
     * Paths the agent mentions are paths inside the rootfs (/workspace/...). Map one back to the
     * real file on the Android side, if it exists.
     */
    fun resolveWorkspaceFile(mentioned: String): File? {
        val cleaned = mentioned.trim().trim('`', '"', '\'', ')', '(', ',', '.')
        val rel = when {
            cleaned.startsWith("/workspace/") -> cleaned.removePrefix("/workspace/")
            cleaned.startsWith("./") -> cleaned.removePrefix("./")
            cleaned.startsWith("~/") -> cleaned.removePrefix("~/")
            cleaned.startsWith("/") -> return File(cleaned).takeIf { it.isFile }
            else -> cleaned
        }
        val f = File(sandbox.workspace, rel)
        return f.takeIf { it.isFile }
    }

    /** Copies a workspace file into Downloads so it leaves the sandbox and can be opened. */
    fun exportFile(file: File, onDone: (String, Uri?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val resolver = getApplication<Application>().contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(
                        android.provider.MediaStore.Downloads.MIME_TYPE,
                        when (file.extension.lowercase()) {
                            "apk" -> "application/vnd.android.package-archive"
                            "png", "jpg", "jpeg" -> "image/*"
                            "zip" -> "application/zip"
                            "json" -> "application/json"
                            "md", "txt", "kt", "java", "py", "c", "h", "sh" -> "text/plain"
                            else -> "application/octet-stream"
                        }
                    )
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("Downloads is not writable")
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                uri
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    { onDone("saved Downloads/${file.name}", it) },
                    { onDone("export failed: ${it.message}", null) }
                )
            }
        }
    }

    // ---------------- terminal ----------------

    fun run(command: String) {
        if (command.isBlank()) return
        terminal.add("$ $command")
        viewModelScope.launch {
            val code = sandbox.shell(command) { line ->
                viewModelScope.launch(Dispatchers.Main) { terminal.add(line) }
            }
            withContext(Dispatchers.Main) {
                if (code != 0) terminal.add("[exit $code]")
                terminal.add("")
            }
        }
    }

    fun clearTerminal() = terminal.clear()

    // ---------------- setup ----------------

    private fun log(line: String) = viewModelScope.launch(Dispatchers.Main) {
        android.util.Log.i("claudecode", line)   // mirrored to logcat so failures are diagnosable
        setupLog = (setupLog + line + "\n").takeLast(4000)
    }

    /** Pulls the official linux-arm64-musl build straight from the npm registry. */
    fun downloadEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                log("resolving @anthropic-ai/claude-code-linux-arm64-musl…")
                val meta = URL("https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/latest")
                    .readText()
                val version = JSONObject(meta).optString("version")
                val tarball = JSONObject(meta).optJSONObject("dist")!!.getString("tarball")
                log("version $version")
                log("downloading… (about 250 MB, this takes a while)")
                URL(tarball).openStream().use { raw ->
                    GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                        val written = extractTar(gz, onlyEntry = "package/claude") { _, stream ->
                            val target = File(sandbox.bin, "claude")
                            target.outputStream().use { out -> stream.copyTo(out, 1 shl 16) }
                            target.setExecutable(true, false)
                            log("installed ${target.absolutePath} (%.0f MB)".format(target.length() / 1048576.0))
                        }
                        if (written == 0) log("archive did not contain package/claude") else verifyEngine()
                    }
                }
            }.onFailure { log("failed: ${it.message}") }
            refreshInstalls()
        }
    }

    /**
     * Imports a picked file and files it by name: proot's helper pieces have to land in
     * specific places or proot dies before it traces anything.
     */
    fun importBinary(uri: Uri, forced: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val picked = forced ?: app.contentResolver.query(uri, null, null, null, null)
                    ?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: "imported"
                val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                sandbox.ensureLayout()

                val target = when {
                    picked.startsWith("libtalloc") -> File(sandbox.lib, "libtalloc.so.2")
                    picked == "loader" || picked == "loader32" -> File(sandbox.libexec, picked)
                    picked.endsWith(".so") || picked.contains(".so.") -> File(sandbox.lib, picked)
                    else -> File(sandbox.bin, picked)
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                target.setExecutable(true, false)
                target.setReadable(true, false)
                log("installed ${target.parentFile?.name}/${target.name} (${bytes.size} bytes)")
                if (target.name == "claude" || target.name == "proot") checkPieces()
            }.onFailure { log("import failed: ${it.message}") }
            refreshInstalls()
        }
    }

    private fun checkPieces() {
        if (sandbox.hasProot && !sandbox.hasLoader)
            log("! proot has no loader — import libexec/proot/loader from Termux")
        if (sandbox.hasProot && !sandbox.hasTalloc)
            log("! libtalloc.so.2 missing — import it from Termux's lib dir")
    }

    // ---------------- anthropic account ----------------

    var loginLog by mutableStateOf("")
        private set
    var loginUrl by mutableStateOf<String?>(null)
        private set
    var loginActive by mutableStateOf(false)
        private set
    var authStatus by mutableStateOf("not checked")
        private set

    private var loginPty: PtyProcess? = null

    private fun loginLine(s: String) = viewModelScope.launch(Dispatchers.Main) {
        android.util.Log.i("claudecode-login", s)
        loginLog = (loginLog + s + "\n").takeLast(4000)
        transcript.add(Turn.Notice(s.trim()))

        // The CLI can't open a browser from inside the rootfs, so the app does it.
        Regex("https://\\S+").find(s)?.value?.let { url ->
            if (loginUrl == null) {
                loginUrl = url
                transcript.add(Turn.Link(url, "open the sign-in page"))
                transcript.add(
                    Turn.Notice("then paste the code back here as a message and press ↵")
                )
                openBrowser(url)
            }
        }
    }

    private fun openBrowser(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Runs `claude auth login --claudeai`, which signs in to your Anthropic account. The CLI owns
     * the credential end to end — it lands in the sandbox's HOME and this app never sees a key.
     */
    fun login(console: Boolean = false) {
        if (!sandbox.engineReady) {
            transcript.add(Turn.Notice("engine not installed — open setup first", isError = true))
            return
        }
        if (loginActive) {
            transcript.add(Turn.Notice("a login is already running — paste the code, or press esc"))
            return
        }
        loginLog = ""; loginUrl = null; loginActive = true
        status = "signing in"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val flag = if (console) "--console" else "--claudeai"
                // Run it on a pseudo-terminal: the CLI reads the code in raw mode and ignores pipes.
                val p = Pty.start(
                    sandbox.prootCommand("/usr/local/bin/claude auth login $flag"),
                    sandbox.env(),
                    sandbox.workspace.absolutePath
                )
                loginPty = p
                val buf = ByteArray(4096)
                val pending = StringBuilder()
                while (true) {
                    val n = p.input.read(buf)
                    if (n <= 0) break
                    pending.append(Pty.clean(String(buf, 0, n)))
                    // flush on line boundaries, but keep partial prompts visible too
                    val text = pending.toString()
                    val cut = text.lastIndexOf('\n')
                    if (cut >= 0) {
                        text.substring(0, cut).lines().forEach { if (it.isNotBlank()) loginLine(it) }
                        pending.setLength(0)
                        pending.append(text.substring(cut + 1))
                    } else if (text.length > 200) {
                        loginLine(text.trim()); pending.setLength(0)
                    }
                }
                if (pending.isNotBlank()) loginLine(pending.toString().trim())
                val code = p.waitFor()
                loginLine(if (code == 0) "— signed in —" else "— exited $code —")
            }.onFailure { loginLine("login failed: ${it.message}") }
            withContext(Dispatchers.Main) { loginActive = false; status = "idle" }
            checkAuth()
            refreshModels()   // the credential exists now, so the real model list is available
        }
    }

    /** Types the pasted authorization code into the CLI's terminal, ending with a carriage return. */
    fun submitCode(code: String) {
        val p = loginPty
        if (p == null) {
            loginLine("[no login session is running — press sign in first]")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = code.trim()
                loginLine("[typing ${text.length} chars + CR into pty pid ${p.pid}]")
                p.writeLine(text)
            }.onFailure { loginLine("[could not send: ${it.message}]") }
        }
    }

    /** Sends a single key to the login terminal — used by the on-screen enter/escape keys. */
    fun sendKey(key: String) {
        val p = loginPty ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                p.output.write(
                    when (key) {
                        "enter" -> "\r".toByteArray()
                        "esc" -> byteArrayOf(0x1B)
                        "ctrl-c" -> byteArrayOf(0x03)
                        "down" -> "\u001B[B".toByteArray()
                        "up" -> "\u001B[A".toByteArray()
                        else -> key.toByteArray()
                    }
                )
                p.output.flush()
                loginLine("[sent $key]")
            }.onFailure { loginLine("[key failed: ${it.message}]") }
        }
    }

    fun cancelLogin() {
        loginPty?.kill()
        loginPty = null
        loginActive = false
    }

    fun checkAuth() {
        if (!sandbox.hasClaude) { authStatus = "engine not installed"; return }
        viewModelScope.launch(Dispatchers.IO) {
            val out = StringBuilder()
            runCatching {
                val p = sandbox.spawn("/usr/local/bin/claude auth status 2>&1")
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    .forEachLine { out.appendLine(it) }
                p.waitFor()
            }
            withContext(Dispatchers.Main) {
                authStatus = out.toString().trim().lines().firstOrNull { it.isNotBlank() }
                    ?: "no response"
            }
        }
    }

    var permissionMode by mutableStateOf("bypassPermissions")
        private set

    /**
     * Models the account can actually use, fetched from the Models API. Until that answers, these
     * are the current families — the CLI accepts full names as well as aliases.
     */
    var models by mutableStateOf(
        listOf(
            ModelInfo("claude-fable-5", "Claude Fable 5"),
            ModelInfo("claude-opus-4-8", "Claude Opus 4.8"),
            ModelInfo("claude-sonnet-5", "Claude Sonnet 5"),
            ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5")
        )
    )
        private set

    var model by mutableStateOf("claude-sonnet-5")
        private set

    var modelsLive by mutableStateOf(false)
        private set

    fun chooseModel(m: String) {
        if (m == model) return
        model = m
        engine.model = m
        engine.stop()   // --model is fixed at launch, so the next turn needs a fresh process
        transcript.add(Turn.Notice("model: $m (new session)"))
    }

    /**
     * Asks the API which models this account may use. The CLI stores its OAuth credential in the
     * sandbox, so the app reads that rather than holding a key of its own — an OAuth token goes on
     * Authorization: Bearer and needs the oauth beta header, unlike an API key.
     */
    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = runCatching {
                val f = File(sandbox.home, ".claude/.credentials.json")
                JSONObject(f.readText()).getJSONObject("claudeAiOauth").getString("accessToken")
            }.getOrNull()
            if (token == null) { log("models: not signed in yet"); return@launch }

            runCatching {
                val conn = (URL("https://api.anthropic.com/v1/models?limit=100").openConnection()
                        as java.net.HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("anthropic-version", "2023-06-01")
                    setRequestProperty("anthropic-beta", "oauth-2025-04-20")
                    connectTimeout = 15000; readTimeout = 15000
                }
                val body = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText()
                else error("HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()?.take(160)}")

                val arr = JSONObject(body).getJSONArray("data")
                val found = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        ModelInfo(it.optString("id"), it.optString("display_name", it.optString("id")))
                    }
                }.filter { it.id.isNotBlank() }
                if (found.isNotEmpty()) withContext(Dispatchers.Main) {
                    models = found
                    modelsLive = true
                    if (models.none { it.id == model }) model = models.first().id
                    log("models: ${found.size} available")
                }
            }.onFailure { log("models: ${it.message}") }
        }
    }

    fun choosePermissionMode(mode: String) {
        permissionMode = mode
        engine.permissionMode = mode
        engine.stop()   // the flag is set at launch, so the next turn starts a fresh CLI
        log("permission mode: $mode (engine will restart)")
    }

    /**
     * Alpine's minirootfs is deliberately tiny — no bash, no git, no grep worth the name. Claude
     * Code's Bash tool wants a real POSIX shell, so pull one in along with the tools it reaches
     * for constantly.
     */
    fun installTools(extra: String = ""): kotlinx.coroutines.Job {
        if (!sandbox.engineReady) { log("engine not ready"); return viewModelScope.launch { } }
        return viewModelScope.launch(Dispatchers.IO) {
            // fakeroot is what lets the unprivileged agent run apk itself afterwards; dpkg/unzip
            // are what termux-install and the SDK installer need.
            val packages = ("bash coreutils findutils grep sed git ripgrep less " +
                "python3 py3-pip nodejs npm curl wget unzip tar xz fakeroot dpkg $extra").trim()
            log("apk add $packages …")
            runCatching {
                // alpine-install downloads and unpacks packages directly. Real apk is unusable on
                // Android (SysV IPC is absent from the kernel), and this needs no root either.
                val p = sandbox.spawn(
                    "rm -f /lib/apk/db/lock; /usr/local/bin/alpine-install $packages 2>&1 | tail -40",
                    asRoot = true
                )
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    .forEachLine { if (it.isNotBlank()) log("  ${it.trim()}") }
                java.io.BufferedReader(java.io.InputStreamReader(p.errorStream))
                    .forEachLine { if (it.isNotBlank() && !it.contains("sanitize")) log("  ! ${it.trim()}") }
                p.waitFor()
            }.onFailure { log("apk failed: ${it.message}") }
            log(if (sandbox.hasBash) "bash installed — POSIX shell ready" else "bash still missing")
            refreshInstalls()
        }
    }

    /**
     * Exercises each privilege path in turn — unprivileged shell, the sudo wrapper, the apk shim,
     * an Android binary — so a failure names its own cause instead of surfacing as "apk broken".
     */
    fun probeSandbox() {
        if (!sandbox.engineReady) { log("probe: engine not ready"); return }
        viewModelScope.launch(Dispatchers.IO) {
            log("--- sandbox probe ---")
            val script = listOf(
                """echo "uid: ${'$'}(id -u)"""",
                """echo "apk resolves to: ${'$'}(command -v apk)"""",
                """echo "sudo: ${'$'}(sudo id -u 2>&1 | head -1)"""",
                """echo "getprop: ${'$'}(getprop ro.product.model 2>&1 | head -1)"""",
                """echo "apk add: ${'$'}(apk add tree 2>&1 | tail -1)"""",
                """echo "tree: ${'$'}(command -v tree || echo missing)""""
            ).joinToString("; ")
            runCatching {
                val p = sandbox.spawn(script)
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    .forEachLine { if (it.isNotBlank()) log("  ${it.trim()}") }
                java.io.BufferedReader(java.io.InputStreamReader(p.errorStream))
                    .forEachLine { if (it.isNotBlank()) log("  ! ${it.trim()}") }
                p.waitFor()
            }.onFailure { log("probe failed: ${it.message}") }

            // Where exactly does apk lose write access?
            log("--- write-permission check ---")
            runCatching {
                val p = sandbox.spawn(
                    listOf(
                        "id -u",
                        "ls -ld / /lib /lib/apk /lib/apk/db /var/cache 2>&1",
                        "touch /lib/apk/db/.probe 2>&1 && echo 'db writable' || echo 'db NOT writable'",
                        "touch /.probe 2>&1 && echo 'root writable' || echo 'root NOT writable'",
                        "rm -f /lib/apk/db/.probe /.probe"
                    ).joinToString("; ")
                )
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    .forEachLine { if (it.isNotBlank()) log("  ${it.trim()}") }
                p.waitFor()
            }.onFailure { log("write check failed: ${it.message}") }
        }
    }

    var buildToolsInstalling by mutableStateOf(false)
        private set

    /**
     * Downloads everything needed to produce an APK on the device: aapt2/d8/apksigner/ecj and a
     * JDK (Android-native aarch64 builds from Termux), Google's android.jar, Gradle, and clang for
     * native code. Roughly 250 MB, so it's a button rather than part of the bundle — and Google's
     * SDK terms don't permit redistributing it inside this APK anyway.
     */
    fun installBuildTools() {
        if (!sandbox.engineReady) { log("engine not ready"); return }
        if (buildToolsInstalling) return
        buildToolsInstalling = true
        viewModelScope.launch(Dispatchers.IO) {
            log("--- android build tools ---")
            runCatching {
                val p = sandbox.spawn(
                    "/usr/local/bin/android-sdk-install 35 2>&1; " +
                        "echo '==> native toolchain'; " +
                        "/usr/local/bin/termux-install clang make binutils 2>&1 | tail -4",
                    asRoot = true
                )
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    .forEachLine { if (it.isNotBlank()) log("  ${it.trim()}") }
                p.waitFor()
            }.onFailure { log("build tools failed: ${it.message}") }
            withContext(Dispatchers.Main) { buildToolsInstalling = false }
            refreshInstalls()
        }
    }

    /** Runs proot directly and echoes everything it says, including the fatal-error text. */
    fun diagnose() {
        viewModelScope.launch {
            setupLog = ""
            log("--- proot ---")
            val proot = sandbox.tool("proot")
            if (proot == null) { log("proot not installed"); return@launch }
            log("binary: ${proot.absolutePath} (${proot.length()} bytes, exec=${proot.canExecute()})")
            log("loader: ${if (sandbox.hasLoader) sandbox.prootLoader.absolutePath else "MISSING"}")
            log("talloc: ${if (sandbox.hasTalloc) "present" else "MISSING"}")
            sandbox.shell("${proot.absolutePath} --version 2>&1 | head -5") { log(it) }
            log("--- rootfs ---")
            log("loader present: ${sandbox.hasMuslLoader}")
            sandbox.shell("ls ${sandbox.rootfs.absolutePath} 2>&1 | head -20") { log(it) }
            if (sandbox.hasProot && sandbox.hasMuslLoader) {
                log("--- proot smoke test ---")
                val cmd = sandbox.prootCommand("echo inside-rootfs; uname -m").joinToString(" ") { "'$it'" }
                sandbox.shell("$cmd 2>&1 | head -20") { log(it) }
            }
            if (sandbox.hasClaude && sandbox.hasProot && sandbox.hasMuslLoader) {
                log("--- claude ---")
                val cmd = sandbox.prootCommand("/usr/local/bin/claude --version")
                    .joinToString(" ") { "'$it'" }
                sandbox.shell("$cmd 2>&1 | head -20") { log(it) }
            }
        }
    }

    /** Unpacks a .tar.gz rootfs (Alpine minirootfs) into the sandbox's rootfs dir. */
    fun importRootfs(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                log("unpacking rootfs…")
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { raw ->
                    GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                        val n = extractTar(gz, into = sandbox.rootfs)
                        log("extracted $n entries")
                    }
                }
                log(
                    if (sandbox.hasMuslLoader) "musl loader found — engine can run"
                    else "warning: /lib/ld-musl-aarch64.so.1 missing from this rootfs"
                )
            }.onFailure { log("rootfs failed: ${it.message}") }
            refreshInstalls()
        }
    }

    /**
     * Minimal tar reader: enough for npm tarballs and Alpine minirootfs (ustar, longlink-free),
     * including the symlinks a rootfs depends on.
     */
    private fun extractTar(
        input: InputStream,
        into: File? = null,
        onlyEntry: String? = null,
        handler: ((String, InputStream) -> Unit)? = null
    ): Int {
        val header = ByteArray(512)
        var count = 0
        while (true) {
            if (!input.readFully(header)) break
            if (header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100).trimEnd(' ', ' ')
            if (name.isEmpty()) break
            val size = String(header, 124, 12).trim { it <= ' ' || it == ' ' }
                .ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val linkName = String(header, 157, 100).trimEnd(' ', ' ')
            val padded = ((size + 511) / 512) * 512

            when {
                onlyEntry != null && name == onlyEntry && handler != null -> {
                    handler(name, LimitedStream(input, size))
                    count++
                    // consume any remaining padding for this entry
                    input.skipExactly(padded - size)
                }

                onlyEntry != null -> input.skipExactly(padded)

                into != null -> {
                    val rel = name.removePrefix("./")
                    val target = File(into, rel)
                    when (type) {
                        '5' -> { target.mkdirs(); input.skipExactly(padded) }
                        '2' -> {
                            target.parentFile?.mkdirs()
                            runCatching { target.delete(); Os.symlink(linkName, target.absolutePath) }
                            input.skipExactly(padded)
                        }
                        '1' -> { input.skipExactly(padded); }
                        else -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                LimitedStream(input, size).copyTo(out, 1 shl 16)
                            }
                            input.skipExactly(padded - size)
                            if (rel.startsWith("bin/") || rel.startsWith("usr/bin/") ||
                                rel.startsWith("sbin/") || rel.startsWith("lib/")
                            ) target.setExecutable(true, false)
                        }
                    }
                    count++
                }

                else -> input.skipExactly(padded)
            }
        }
        return count
    }

    private fun InputStream.readFully(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun InputStream.skipExactly(n: Long) {
        var left = n
        val junk = ByteArray(8192)
        while (left > 0) {
            val got = read(junk, 0, minOf(junk.size.toLong(), left).toInt())
            if (got < 0) return
            left -= got
        }
    }

    private class LimitedStream(val inner: InputStream, var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = inner.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}
