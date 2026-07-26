package app.cinder

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Cinder's palette: near-black ground, an ember accent, muted chrome.
private val Ink = Color(0xFF15130F)
private val Panel = Color(0xFF1D1A15)
private val Line = Color(0xFF2E2A23)
private val Text0 = Color(0xFFE8E3D9)
private val Dim = Color(0xFF8A8377)
private val Orange = Color(0xFFE0603A)   // ember
private val Green = Color(0xFF7FB069)
private val Red = Color(0xFFD9534F)
private val Blue = Color(0xFF6C9BD1)

private val mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    private val vm: Session by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = Ink, background = Ink,
                    surface = Panel, onSurface = Text0, onBackground = Text0
                )
            ) { Root(vm) }
        }
    }
}

private enum class Tab(val label: String) { Chat("chat"), Terminal("shell"), Setup("setup") }

/** Lets deep composables (the file chips) reach the session without threading it through. */
private val LocalSession = compositionLocalOf<Session?> { null }

@Composable
private fun Root(vm: Session) {
    var tab by remember { mutableStateOf(Tab.Chat) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalSession provides vm) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Ink, drawerContentColor = Text0) {
                    HistoryPane(vm) { record ->
                        vm.openSession(record)
                        tab = Tab.Chat
                        scope.launch { drawer.close() }
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = Ink,
                topBar = {
                    Header(vm, tab, onMenu = { scope.launch { drawer.open() } }) { tab = it }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        Tab.Chat -> ChatTab(vm)
                        Tab.Terminal -> TerminalTab(vm)
                        Tab.Setup -> SetupTab(vm)
                    }
                    // HTML preview rides above the current tab so it can be opened from anywhere.
                    vm.previewFile?.let { HtmlPreview(it) { vm.closePreview() } }
                }
            }
        }
    }
}

@Composable
private fun HistoryPane(vm: Session, onOpen: (SessionRecord) -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", color = Orange, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text("sessions", color = Text0, fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${vm.history.size}", color = Dim, fontFamily = mono, fontSize = 11.sp)
            if (vm.history.isNotEmpty()) {
                Text(
                    "clear all", color = Red, fontFamily = mono, fontSize = 10.sp,
                    modifier = Modifier.clickable { vm.clearHistory() }.padding(start = 10.dp)
                )
            }
        }
        HorizontalDivider(color = Line)

        if (vm.history.isEmpty()) {
            Text(
                "Conversations show up here once they've had a reply. Tapping one resumes it — " +
                    "the CLI reloads that session's full context, not just this transcript.",
                color = Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(vm.history) { rec ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Panel),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f).clickable { onOpen(rec) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(rec.title, color = Text0, fontSize = 12.sp, maxLines = 2, lineHeight = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ago(rec.updated), color = Dim, fontFamily = mono, fontSize = 9.sp)
                            Text(
                                rec.model.removePrefix("claude-"),
                                color = Dim, fontFamily = mono, fontSize = 9.sp
                            )
                            Text(
                                "${rec.turns.count { it.first == "u" }} turns",
                                color = Dim, fontFamily = mono, fontSize = 9.sp
                            )
                        }
                    }
                    Text(
                        "×", color = Dim, fontFamily = mono, fontSize = 16.sp,
                        modifier = Modifier.clickable { vm.deleteSession(rec) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        else -> "${d / 86400}d ago"
    }
}

@Composable
private fun Header(vm: Session, tab: Tab, onMenu: () -> Unit, onTab: (Tab) -> Unit) {
    Column(
        Modifier.background(Ink).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "☰", color = Dim, fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onMenu() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text("✦", color = Orange, fontSize = 18.sp)
            Text("cinder", color = Text0, fontSize = 16.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(vm.status, color = if (vm.busy) Orange else Dim, fontSize = 11.sp, fontFamily = mono)
            Surface(
                color = Panel,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                onClick = { vm.newSession() }
            ) {
                Text(
                    "+ new", color = Dim, fontFamily = mono, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            ModelPicker(vm)
        }
        Text(
            vm.cwd.replace(Regex("^/data/(user/0|data)/[^/]+/files"), "~"),
            color = Dim, fontSize = 10.sp, fontFamily = mono, maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Surface(
                    color = if (on) Panel else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Orange.copy(alpha = .5f) else Line),
                    onClick = { onTab(t) }
                ) {
                    Text(
                        t.label, color = if (on) Orange else Dim, fontFamily = mono, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun ModelPicker(vm: Session) {
    var open by remember { mutableStateOf(false) }
    val current = vm.models.firstOrNull { it.id == vm.model }
    Box {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = .4f)),
            onClick = { open = true }
        ) {
            Text(
                // short label in the bar, exact id in the menu
                (current?.displayName ?: vm.model).removePrefix("Claude ") + " ▾",
                color = Orange, fontFamily = mono, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Panel) {
            Text(
                if (vm.modelsLive) "available to your account" else "defaults — sign in to refresh",
                color = Dim, fontFamily = mono, fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            vm.models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                m.displayName, fontFamily = mono, fontSize = 12.sp,
                                color = if (m.id == vm.model) Orange else Text0
                            )
                            Text(m.id, fontFamily = mono, fontSize = 9.sp, color = Dim)
                        }
                    },
                    onClick = { vm.chooseModel(m.id); open = false }
                )
            }
            HorizontalDivider(color = Line)
            DropdownMenuItem(
                text = { Text("refresh list", fontFamily = mono, fontSize = 11.sp, color = Dim) },
                onClick = { vm.refreshModels(); open = false }
            )
        }
    }
}

// ---------------- chat ----------------

@Composable
private fun ChatTab(vm: Session) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.transcript.size, vm.responding) {
        if (vm.transcript.isNotEmpty()) scope.launch { listState.animateScrollToItem(vm.transcript.size) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (vm.transcript.isEmpty()) item { Welcome() }
            items(vm.transcript) { TurnRow(it) }
            if (vm.responding) item { RespondingMarker(vm.responseTokens) }
        }
        Composer(vm)
    }
}

/**
 * Live marker shown while the model is producing output. Claude Code sends complete message blocks
 * rather than a token stream, so this reflects how much has arrived so far — a rough ~4-chars-per-
 * token estimate that ticks up as each block lands.
 */
@Composable
private fun RespondingMarker(tokens: Int) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⏺", color = Orange, fontSize = 12.sp, modifier = Modifier.graphicsLayer { this.alpha = alpha })
        Spacer(Modifier.width(6.dp))
        Text("responding…", color = Text0, fontFamily = mono, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text("~$tokens tokens", color = Dim, fontFamily = mono, fontSize = 11.sp)
    }
}

@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("✦ Cinder — a mobile Claude Code client", color = Orange, fontFamily = mono, fontSize = 13.sp)
        Text(
            "This app carries its own sandbox: a private Linux workspace where files are created, " +
                "edited and built. The shell tab works right now. The agent needs its engine — see setup.",
            color = Dim, fontSize = 12.sp, lineHeight = 18.sp
        )
    }
}

@Composable
private fun TurnRow(turn: Turn) {
    when (turn) {
        is Turn.User -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(">", color = Dim, fontFamily = mono, fontSize = 13.sp)
            SelectionContainer { Text(turn.text, color = Text0, fontSize = 13.sp, lineHeight = 19.sp) }
        }

        is Turn.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val parts = remember(turn.text) { splitFences(turn.text) }
            parts.forEachIndexed { i, part ->
                when (part) {
                    is Part.Prose -> if (part.text.isNotBlank()) Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // only the first prose block carries the marker; the rest continue it
                        Text(
                            if (i == 0) "⏺" else " ",
                            color = Orange, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        SelectionContainer {
                            Text(part.text, color = Text0, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }

                    is Part.Code -> CodeCanvas(part)
                }
            }
            FileChips(turn.text)
        }

        is Turn.Thinking -> {
            var open by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = if (turn.text.isNotBlank())
                        Modifier.clip(RoundedCornerShape(3.dp)).clickable { open = !open }
                    else Modifier
                ) {
                    Text("✻", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        if (turn.text.isBlank()) "thinking…"
                        else if (open) "thinking" else "thinking · tap to read",
                        color = Dim, fontFamily = mono, fontSize = 11.sp
                    )
                }
                // Reasoning text only exists when the CLI is set to summarize it; otherwise the
                // block arrives empty and the marker alone is the signal.
                if (open && turn.text.isNotBlank()) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Panel)
                            .border(1.dp, Line, RoundedCornerShape(3.dp)).padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(turn.text, color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }

        is Turn.Tool -> ToolCard(turn)

        is Turn.Notice -> Text(
            turn.text,
            color = if (turn.isError) Red else Dim,
            fontSize = 11.sp, fontFamily = mono, lineHeight = 16.sp
        )

        is Turn.Link -> {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(turn.url))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
            ) { Text(turn.label, fontFamily = mono, fontSize = 12.sp) }
        }

        is Turn.Done -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⎿", color = Dim, fontFamily = mono, fontSize = 12.sp)
            Text(turn.summary, color = Dim, fontFamily = mono, fontSize = 11.sp)
        }
    }
}

/** A message splits into prose and fenced code; the code gets its own canvas. */
private sealed interface Part {
    data class Prose(val text: String) : Part
    data class Code(val language: String, val code: String, val open: Boolean) : Part
}

/**
 * Splits on ``` fences. A canvas opens at the opening fence and closes at the closing one — and if
 * a message ends mid-block (the closing fence never arrives), the canvas stays open rather than
 * spilling code into the prose.
 */
private fun splitFences(text: String): List<Part> {
    val parts = mutableListOf<Part>()
    val lines = text.lines()
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    var closed = true

    fun flushProse() {
        if (buf.isNotEmpty()) { parts.add(Part.Prose(buf.toString().trimEnd('\n'))); buf.clear() }
    }

    for (line in lines) {
        val fence = line.trimStart().startsWith("```")
        if (fence && !inCode) {
            flushProse()
            inCode = true; closed = false
            lang = line.trimStart().removePrefix("```").trim()
        } else if (fence && inCode) {
            parts.add(Part.Code(lang, buf.toString().trimEnd('\n'), open = false))
            buf.clear(); inCode = false; closed = true
        } else {
            buf.appendLine(line)
        }
    }
    if (inCode) parts.add(Part.Code(lang, buf.toString().trimEnd('\n'), open = !closed))
    else flushProse()
    return parts
}

@Composable
private fun CodeCanvas(part: Part.Code) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lineCount = remember(part.code) { part.code.lines().size }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .background(Ink).border(1.dp, Line, RoundedCornerShape(5.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                part.language.ifBlank { "code" },
                color = Orange, fontFamily = mono, fontSize = 10.sp
            )
            Text("$lineCount lines", color = Dim, fontFamily = mono, fontSize = 9.sp)
            if (part.open) Text("writing…", color = Blue, fontFamily = mono, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (copied) "copied" else "copy",
                color = if (copied) Green else Dim, fontFamily = mono, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(3.dp))
                    .clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(part.code))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        SelectionContainer {
            Text(
                part.code,
                color = Text0, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)
            )
        }
    }
}

/**
 * Any workspace path the model mentions becomes a chip. Tapping copies the file out of the
 * sandbox into Downloads and opens it — that's what makes "here's your file" actually usable,
 * since nothing outside the app can read app-private storage.
 */
@Composable
private fun FileChips(text: String) {
    val vm = LocalSession.current ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }

    val candidates = remember(text) {
        Regex("""(?:/workspace/|\./|~/)?[\w.\-/]+\.[A-Za-z0-9]{1,6}""")
            .findAll(text).map { it.value }.distinct().take(8).toList()
    }
    val files = remember(candidates) { candidates.mapNotNull { vm.resolveWorkspaceFile(it) }.distinct() }
    if (files.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { f ->
            val isHtml = f.extension.lowercase() in listOf("html", "htm")
            val isApk = f.extension.lowercase() == "apk"
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.exportFile(f) { msg, uri ->
                            note = msg
                            if (uri != null) runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        .setDataAndType(uri, ctx.contentResolver.getType(uri))
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Blue)
                ) {
                    Text(
                        "⤓  ${f.name}  ${f.length() / 1024 + 1} KB",
                        fontFamily = mono, fontSize = 11.sp, maxLines = 1
                    )
                }
                if (isHtml) OutlinedButton(
                    onClick = { vm.previewHtml(f) },
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Orange)
                ) { Text("▶ preview", fontFamily = mono, fontSize = 11.sp, maxLines = 1) }
                if (isApk) OutlinedButton(
                    onClick = { vm.installApk(f) },
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Orange)
                ) { Text("⇩ install", fontFamily = mono, fontSize = 11.sp, maxLines = 1) }
            }
        }
        note?.let { Text(it, color = Dim, fontFamily = mono, fontSize = 10.sp) }
    }
}

/**
 * Full-screen WebView preview of an HTML file from the workspace. JavaScript and file access are on
 * so a self-contained page (inline or same-directory CSS/JS) renders as it would in a browser; the
 * base URL is the file's own directory so relative asset paths resolve.
 */
@Composable
private fun HtmlPreview(file: java.io.File, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("▶ ${file.name}", color = Text0, fontFamily = mono, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("✕ close", color = Orange, fontFamily = mono, fontSize = 12.sp) }
        }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    webViewClient = android.webkit.WebViewClient()
                }
            },
            update = { web ->
                val html = runCatching { file.readText() }.getOrDefault("<h1>could not read ${file.name}</h1>")
                web.loadDataWithBaseURL(
                    "file://${file.parent}/", html, "text/html", "utf-8", null
                )
            }
        )
    }
}

@Composable
private fun ToolCard(tool: Turn.Tool) {
    var open by remember { mutableStateOf(false) }
    val accent = when {
        tool.failed -> Red
        tool.result != null -> Green
        else -> Blue
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.clip(RoundedCornerShape(3.dp))
        ) {
            Text("⏺", color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tool.name, color = Text0, fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(tool.headline, color = Dim, fontFamily = mono, fontSize = 12.sp, maxLines = 1)
                }
                // Live status for anything touching a file — Read, Write/Edit, or a Bash redirect.
                val writes = tool.name in setOf("Write", "Edit", "NotebookEdit")
                val reads = tool.name == "Read"
                if (writes || reads || tool.lines != null) {
                    val lines = tool.lines
                    Text(
                        when {
                            reads && !tool.finished -> "reading file…"
                            reads && tool.failed -> "read failed"
                            reads && lines != null -> "read file · $lines lines"
                            reads -> "read file"
                            !tool.finished && lines != null -> "creating file · $lines lines written"
                            !tool.finished -> "creating file…"
                            tool.failed -> "write failed"
                            lines != null -> "file created · $lines lines"
                            else -> "file created"
                        },
                        color = if (tool.failed) Red
                        else if (reads) Blue
                        else if (tool.finished) Green
                        else Blue,
                        fontFamily = mono, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (tool.body != null) {
                    Box(
                        Modifier.padding(top = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp)).background(Panel)
                            .border(1.dp, Line, RoundedCornerShape(3.dp)).padding(8.dp)
                    ) {
                        Text(
                            tool.body, fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp,
                            color = Text0
                        )
                    }
                }
                tool.result?.let { r ->
                    val lines = r.lines()
                    val expandable = lines.size > 1 || r.length > 90
                    Row(
                        // the whole row toggles, so the tap target isn't a 10sp word
                        Modifier.padding(top = 4.dp)
                            .then(
                                if (expandable) Modifier.clip(RoundedCornerShape(3.dp))
                                    .clickable { open = !open }
                                else Modifier
                            ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("⎿", color = Dim, fontFamily = mono, fontSize = 11.sp)
                        Text(
                            if (open) r else lines.first().take(90),
                            color = if (tool.failed) Red else Dim,
                            fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (expandable) {
                            Text(
                                if (open) "less" else "+${lines.size - 1} more",
                                color = Orange, fontFamily = mono, fontSize = 10.sp,
                                modifier = Modifier.clip(RoundedCornerShape(2.dp))
                                    .background(Panel)
                                    .clickable { open = !open }
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: Session) {
    var text by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.attachFile(it) }
    }
    // navigationBarsPadding keeps it clear of the gesture bar; imePadding lifts it only while the
    // keyboard is up. Order matters — insets are consumed outermost first.
    Column(Modifier.background(Panel).navigationBarsPadding().imePadding().padding(10.dp)) {
        if (vm.attachments.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                vm.attachments.forEach { f ->
                    Row(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(Ink)
                            .border(1.dp, Line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "📎 ${f.name}", color = Blue, fontFamily = mono,
                            fontSize = 10.sp, maxLines = 1
                        )
                        Text(
                            "×", color = Dim, fontFamily = mono, fontSize = 12.sp,
                            modifier = Modifier.clickable { vm.removeAttachment(f) }
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = Ink,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                onClick = { pick.launch(arrayOf("*/*")) }
            ) {
                Text(
                    "+", color = Dim, fontFamily = mono, fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("ask claude…", fontFamily = mono, fontSize = 13.sp, color = Dim) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Text0, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange, unfocusedBorderColor = Line, cursorColor = Orange
                ),
                shape = RoundedCornerShape(4.dp),
                maxLines = 5
            )
            if (vm.busy) {
                OutlinedButton(
                    onClick = { vm.interrupt() },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
                ) { Text("esc", fontFamily = mono, fontSize = 12.sp) }
            } else {
                Button(
                    onClick = { vm.send(text); text = "" },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
                ) { Text("↵", fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------- terminal ----------------

@Composable
private fun TerminalTab(vm: Session) {
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.terminal.size) {
        if (vm.terminal.isNotEmpty()) scope.launch { listState.animateScrollToItem(vm.terminal.size - 1) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(Ink),
            contentPadding = PaddingValues(12.dp)
        ) {
            if (vm.terminal.isEmpty()) {
                item {
                    Text(
                        "sandbox shell — /system/bin/sh in this app's private workspace.\n" +
                            "try: pwd · ls -la · echo hi > a.txt · cat a.txt · uname -a",
                        color = Dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }
            items(vm.terminal) { line ->
                Text(
                    line,
                    color = if (line.startsWith("$ ")) Orange else if (line.startsWith("[exit")) Red else Text0,
                    fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
        }
        Row(
            Modifier.background(Panel).navigationBarsPadding().imePadding().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("sh command", fontFamily = mono, fontSize = 12.sp, color = Dim) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Text0, fontSize = 12.sp, fontFamily = mono),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange, unfocusedBorderColor = Line, cursorColor = Orange
                ),
                shape = RoundedCornerShape(4.dp),
                singleLine = true
            )
            Button(
                onClick = { vm.run(cmd); cmd = "" },
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
            ) { Text("run", fontFamily = mono, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { vm.clearTerminal() },
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim)
            ) { Text("clr", fontFamily = mono, fontSize = 11.sp) }
        }
    }
}

// ---------------- setup ----------------

@Composable
private fun SetupTab(vm: Session) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.importBinary(it) }
    }
    val pickRootfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importRootfs(it) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(14.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("account", color = Dim, fontFamily = mono, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(vm.authStatus, color = Dim, fontFamily = mono, fontSize = 10.sp, maxLines = 1)
        }
        Text(
            "Sign in from the chat tab with /login — it runs the CLI's own OAuth on a terminal, " +
                "opens the page in your browser, and takes the code back as a chat message.",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp
        )
        HorizontalDivider(color = Line)
        Text("engine", color = Dim, fontFamily = mono, fontSize = 11.sp)
        vm.installs.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (i.present) "●" else "○", color = if (i.present) Green else Dim, fontSize = 12.sp)
                Column(Modifier.weight(1f)) {
                    Text(i.label, color = Text0, fontSize = 13.sp, fontFamily = mono)
                    Text(i.detail, color = Dim, fontSize = 11.sp)
                }
            }
        }

        Text(
            "proot, its loader, libtalloc and a musl rootfs ship inside this APK and install themselves " +
                "on first launch. Claude Code is not bundled — it's Anthropic's binary, so the button " +
                "below pulls it from npm, and it logs in with its own OAuth. No API key is stored here.",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp
        )

        Button(
            onClick = { vm.downloadEngine() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
        ) { Text("download claude (arm64 musl)", fontFamily = mono, fontSize = 12.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.bootstrap(force = true) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Text0)
            ) { Text("reinstall runtime", fontFamily = mono, fontSize = 11.sp) }
            OutlinedButton(
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim)
            ) { Text("import file", fontFamily = mono, fontSize = 11.sp) }
        }

        Button(
            onClick = { vm.installTools() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
        ) { Text("install shell + tools (bash, git, ripgrep)", fontFamily = mono, fontSize = 12.sp) }

        Button(
            onClick = { vm.installBuildTools() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.buildToolsInstalling,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Orange)
        ) {
            Text(
                if (vm.buildToolsInstalling) "downloading… watch the log below"
                else "install android build tools (sdk, gradle, ndk)",
                fontFamily = mono, fontSize = 12.sp
            )
        }
        Text(
            "About 250 MB: aapt2, d8, apksigner, ecj and a JDK (Android-native aarch64 builds), " +
                "Google's android.jar for API 35, Gradle, plus clang/make for native code. " +
                "Downloaded rather than bundled — Google's SDK terms don't allow shipping it " +
                "inside this APK, and the NDK proper is x86-64 only, so clang is the ARM stand-in.",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp
        )

        Button(
            onClick = { vm.installApkTools() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.apkToolsInstalling,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Orange)
        ) {
            Text(
                if (vm.apkToolsInstalling) "downloading… watch the log below"
                else "install apk tools (apktool, jadx, dex2jar)",
                fontFamily = mono, fontSize = 12.sp
            )
        }
        Text(
            "Takes APKs apart: apktool for smali and resources, jadx for Java source, dex2jar, " +
                "plus aapt2/apksigner and a JDK. baksmali and smali are provided by apktool, " +
                "which is built on them. Around 200 MB, downloaded rather than bundled.",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp
        )

        Text("permissions", color = Dim, fontFamily = mono, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "bypassPermissions" to "allow everything",
                "acceptEdits" to "edits only"
            ).forEach { (mode, label) ->
                val on = vm.permissionMode == mode
                OutlinedButton(
                    onClick = { vm.choosePermissionMode(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (on) Orange else Panel,
                        contentColor = if (on) Ink else Dim
                    )
                ) { Text(label, fontFamily = mono, fontSize = 11.sp, maxLines = 1) }
            }
        }
        Text(
            "Headless has nobody to answer a permission prompt, so an unattended request just gets " +
                "declined — that's why writes failed. The workspace is app-private and disposable, " +
                "so the default grants them up front.",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp
        )

        Button(
            onClick = { vm.diagnose() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Orange)
        ) { Text("diagnose (show proot's actual output)", fontFamily = mono, fontSize = 12.sp) }

        if (vm.setupLog.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(3.dp)).padding(8.dp)
            ) {
                Text(vm.setupLog, color = Text0, fontFamily = mono, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }

        OutlinedButton(
            onClick = { vm.refreshInstalls() },
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim)
        ) { Text("refresh", fontFamily = mono, fontSize = 11.sp) }
    }
}
