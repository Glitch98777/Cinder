package app.cinder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Drives the real Claude Code binary inside the sandbox and turns its stream-json output into
 * typed events the UI can render.
 *
 * The CLI is run headless:
 *   claude --print --input-format stream-json --output-format stream-json --verbose
 * which speaks newline-delimited JSON both ways. Authentication is the CLI's own — it logs in
 * with OAuth and stores the credential under the sandbox's HOME, so this app never handles an
 * API key or a token itself.
 */
/**
 * Handed to the CLI with --append-system-prompt. It runs inside Alpine on a phone, which is not
 * what it would otherwise assume.
 */
private val SANDBOX_BRIEF = listOf(
    "You are running on an Android phone inside an Alpine Linux (musl, aarch64) sandbox under proot.",
    "The working directory /workspace is writable and persistent; /root is your home.",
    "System packages: apk add <pkg> / apk del <pkg>. These run real apk elevated via nested proot, " +
        "so full dependency trees install. You'll see a harmless 'database inconsistent' note at the " +
        "end — ignore it, the files are already installed and work.",
    "npm install -g works: the prefix is /root/.npm-global, already on PATH.",
    "pip install works: it installs to /root/.local (PIP_USER is set), also on PATH. Use python3/pip3.",
    "If a language runtime is missing, install it first: apk add nodejs npm python3 py3-pip openjdk17 go rust.",
    "Android build tools are NOT in Alpine, and Google's SDK/NDK are x86-64 only so they cannot run here.",
    "Use termux-install <pkg> for Android/aarch64 builds of them: termux-install aapt2 d8 apksigner openjdk-17.",
    "To take an APK apart: apktool d <apk> (smali + resources), jadx <apk> (Java source).",
    "baksmali <apk> [out] and smali <dir> [out.apk] are shims onto apktool, which embeds both.",
    "If those are missing, run apk-tools-install once — it installs Alpine's JDK and the jars.",
    "Termux/Android binaries DO run here: their ELF interpreter is /system/bin/linker64 (a symlink " +
        "into /apex that proot can't resolve), so termux-install runs android-bootstrap, which copies " +
        "the real bionic linker plus a flat set of .so files into the rootfs and rewrites each binary's " +
        "interpreter and rpath to use them. If an Android binary ever reports 'not found' for its loader " +
        "or a library, just run: android-bootstrap — it re-copies and re-patches everything.",
    "Those land in /data/data/com.termux/files/usr and are already on PATH. Cross-compilers: termux-install clang binutils make cmake.",
    "To read device/system properties, run getprop by that bare name only (it is a wrapper on PATH " +
        "that returns instantly): getprop lists everything, getprop ro.product.model returns one " +
        "value. Never invoke it by an absolute path such as /system/bin/getprop — that path does NOT " +
        "resolve in this sandbox and will fail. There is no working dumpsys/pm/am/service here either.",
    "To install an APK you built onto the phone, run: install-apk <path-to.apk>. It hands the file " +
        "to Android's package installer, which asks the user to confirm — you can't install silently. " +
        "Run it ONCE per APK and wait; do not call it repeatedly, that just spams install prompts.",
    "To show the user an HTML page (one they uploaded to /workspace, or one you wrote), run: " +
        "preview <path-to.html>. It renders in an in-app WebView (JavaScript + same-directory CSS/JS " +
        "work). The user can also tap 'preview' on any .html file chip in the chat.",
    "To ping the user with a phone notification after a delay, run: notify <seconds> <title> [body]. " +
        "e.g. notify 60 \"Break time\" \"Step away from the screen\". It fires even if the app is in the " +
        "background. Use it for reminders or to signal a long task finished at a set time. Call it " +
        "ONCE per reminder; do not loop or repeat the same notify, that just spams notifications.",
    "Files you create in /workspace can be opened by the user from the app, so mention their paths."
).joinToString(" ")

sealed interface Event {
    /** Assistant prose — rendered under a ⏺ marker. */
    data class Message(val text: String) : Event
    /** A thinking block. Its text is empty unless the CLI is set to summarize reasoning. */
    data class Thinking(val text: String) : Event
    /** The model called a tool: Read, Edit, Write, Bash, Glob, … */
    data class ToolCall(val id: String, val name: String, val input: JSONObject) : Event
    /** The result that came back for a tool call. */
    data class ToolResult(val id: String, val content: String, val isError: Boolean) : Event
    /** Turn finished; carries cost/duration when the CLI reports them. */
    data class Done(val subtype: String, val costUsd: Double?, val durationMs: Long?) : Event
    /** Session metadata from the init frame — session_id is what makes a turn resumable. */
    data class Init(
        val model: String?, val cwd: String?, val tools: List<String>, val sessionId: String?
    ) : Event
    /** Anything the bridge itself needs to say — startup, crashes. */
    data class Notice(val text: String, val isError: Boolean = false) : Event
    /** A stderr line from the engine — shown for visibility, but never ends the turn. */
    data class Stderr(val text: String) : Event
}

class Engine(private val sandbox: Sandbox, private val scope: CoroutineScope) {

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    val running get() = process?.isAlive == true

    /** "bypassPermissions" (the sandbox default) or "acceptEdits" for edits-only. */
    var permissionMode: String = "bypassPermissions"

    /** Model id handed to --model; empty means the CLI's own default. */
    var model: String = "claude-sonnet-5"

    /** When set, the next launch resumes that stored session instead of starting fresh. */
    var resumeId: String? = null

    /** Starts the CLI. [onEvent] is called from IO threads — hop to the main thread in the UI. */
    fun start(onEvent: (Event) -> Unit) {
        if (running) return
        if (!sandbox.engineReady) {
            onEvent(Event.Notice("Engine not installed — see Setup", isError = true))
            return
        }
        val inner = buildString {
            append("exec /usr/local/bin/claude ")
            append("--print ")
            append("--input-format stream-json ")
            append("--output-format stream-json ")
            // Headless has no way to answer a permission prompt: an unattended request is simply
            // declined, which is what turned every Write into "permission denied". The workspace
            // is app-private and disposable, so the sandbox grants them up front.
            //
            // Note there's no --dangerously-skip-permissions here: the CLI rejects that flag
            // outright when it detects root, and it adds nothing over the permission mode.
            append("--permission-mode $permissionMode ")
            if (model.isNotBlank()) append("--model $model ")
            resumeId?.let { append("--resume $it ") }
            // Without this the model assumes a normal Linux box and reaches for apt/brew/sudo-less
            // apk, all of which fail here.
            append("--append-system-prompt ")
            // The brief is embedded inside a double-quoted shell argument, so any backtick or $ in
            // it would be command-substituted by /bin/sh and wreck the launch ("unexpected end of
            // file"). Escape the four chars that stay special inside double quotes so the brief text
            // is passed through literally no matter what it contains.
            val safeBrief = SANDBOX_BRIEF
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`")
            append('"').append(safeBrief).append("\" ")
            append("--verbose")
        }
        val cmd = sandbox.prootCommand(inner)
        val pb = ProcessBuilder(*cmd)
            .directory(sandbox.workspace)
            .redirectErrorStream(false)
        pb.environment().putAll(sandbox.env().associate { it.substringBefore('=') to it.substringAfter('=') })

        val p = try {
            pb.start()
        } catch (e: Exception) {
            onEvent(Event.Notice("Could not start engine: ${e.message}", isError = true))
            return
        }
        process = p
        stdin = BufferedWriter(OutputStreamWriter(p.outputStream))

        // Both readers must swallow their own failures: destroying the process to start a new
        // session closes these streams mid-read, and an uncaught IOException inside a coroutine
        // takes the whole app down.
        scope.launch(Dispatchers.IO) {
            runCatching {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                    if (line.isNotBlank()) parse(line, onEvent)
                }
            }
            if (process === p) {
                onEvent(Event.Notice("engine exited (${runCatching { p.waitFor() }.getOrDefault(-1)})"))
                process = null
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                BufferedReader(InputStreamReader(p.errorStream)).forEachLine { line ->
                    // proot prints harmless "can't sanitize binding" warnings to stderr on every
                    // spawn; they are noise, not turn-ending errors, so drop them entirely.
                    if (line.isNotBlank() && !line.contains("proot warning") &&
                        !line.contains("can't sanitize") && !line.contains("linkerconfig")
                    ) {
                        onEvent(Event.Stderr(line))
                    }
                }
            }
        }
    }

    fun send(text: String) {
        val w = stdin ?: return
        val msg = JSONObject()
            .put("type", "user")
            .put(
                "message", JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            )
        scope.launch(Dispatchers.IO) {
            runCatching {
                w.write(msg.toString()); w.write("\n"); w.flush()
            }
        }
    }

    fun stop() {
        runCatching { stdin?.close() }
        process?.destroy()
        process = null
    }

    /** Tolerant parser: unknown frames are ignored rather than crashing the stream. */
    private fun parse(line: String, onEvent: (Event) -> Unit) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("type")) {
            "system" -> if (json.optString("subtype") == "init") {
                val tools = json.optJSONArray("tools")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList()
                onEvent(
                    Event.Init(
                        json.optString("model", null),
                        json.optString("cwd", null),
                        tools,
                        json.optString("session_id", null)
                    )
                )
            }

            "assistant" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> block.optString("text").takeIf { it.isNotBlank() }
                            ?.let { onEvent(Event.Message(it)) }

                        "thinking", "redacted_thinking" ->
                            onEvent(Event.Thinking(block.optString("thinking")))

                        "tool_use" -> onEvent(
                            Event.ToolCall(
                                block.optString("id"),
                                block.optString("name"),
                                block.optJSONObject("input") ?: JSONObject()
                            )
                        )
                    }
                }
            }

            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "tool_result") {
                        onEvent(
                            Event.ToolResult(
                                block.optString("tool_use_id"),
                                stringify(block.opt("content")),
                                block.optBoolean("is_error", false)
                            )
                        )
                    }
                }
            }

            "result" -> onEvent(
                Event.Done(
                    json.optString("subtype", "success"),
                    json.optDouble("total_cost_usd").takeIf { !it.isNaN() },
                    json.optLong("duration_ms").takeIf { it > 0 }
                )
            )
        }
    }

    /** tool_result content is a string on some frames and a block array on others. */
    private fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).mapNotNull { i ->
            val o = value.optJSONObject(i)
            when (o?.optString("type")) {
                "text" -> o.optString("text")
                null -> value.optString(i)
                else -> null
            }
        }.joinToString("\n")
        else -> value.toString()
    }
}
