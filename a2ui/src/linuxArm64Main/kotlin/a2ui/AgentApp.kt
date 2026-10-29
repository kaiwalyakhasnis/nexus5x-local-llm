package a2ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import ui.Alignment
import ui.Arrangement
import ui.Box
import ui.Column
import ui.Device
import ui.Modifier
import ui.Row
import ui.a2ui.A2uiClient
import ui.a2ui.A2uiView
import ui.a2ui.Action
import ui.a2ui.A2UI_VERSION
import ui.a2ui.BASIC_CATALOG
import ui.a2ui.FocusScroll
import ui.a2ui.FocusScrollState
import ui.a2ui.ScrollIndicator
import ui.a2ui.Json
import ui.a2ui.Surface
import ui.a2ui.encode
import ui.a2ui.obj
import ui.a2ui.string
import ui.background
import ui.dp
import ui.fillMaxSize
import ui.fillMaxWidth
import ui.height
import ui.log
import ui.material.ButtonStyle
import ui.material.CircularProgressIndicator
import ui.material.Icon
import ui.material.IconButton
import ui.material.MaterialTheme
import ui.material.Text
import ui.material.TopAppBar
import ui.material.Typography
import ui.material.darkColorScheme
import ui.padding
import ui.weight

/** Material 3's baseline dark scheme; the screen is cleared to its surface (Main.kt). */
internal val COLORS = darkColorScheme()

/**
 * Everything the screen does: the A2UI client and its surfaces, the local
 * agent, and the link port where other programs send questions or A2UI
 * messages of their own (and hear the actions).
 */
private class Controller(private val scope: CoroutineScope, llm: Http, private val port: Int) {
    val agent = LocalAgent(llm)
    val client = A2uiClient(::onAction)
    private val link = Link(port, ::onLine)
    /** Hold VOLUME UP and ask: the model picks the screen (no pattern). */
    val voice = Voice(scope) { ask(it) }
    /** The question the screen answers, shown above it. */
    var question by mutableStateOf("")
    private var job: Job? = null
    private var warm: Job? = null
    /** The answer's scroll: VOLUME DOWN reads through it a page at a time before moving to its controls. */
    var scroll: FocusScrollState? = null
    /** Changes with every new surface, so the scroll position starts again at the top. */
    var generation by mutableIntStateOf(0)

    fun start() {
        scope.launch { link.run() }
        scope.launch {
            while (true) {
                val before = agent.model
                agent.poll()
                if (agent.model != before) {
                    cancel()
                    agent.forget()
                    if (agent.model != null) {
                        showHome()
                        warm = scope.launch { agent.warmUp() }
                    } else {
                        clear()
                    }
                }
                delay(if (agent.model == null) 2000 else 5000)
            }
        }
    }

    /** Applies a server-to-client message and tells the link about it. */
    fun apply(m: Json.Obj) {
        if (m["createSurface"] != null) {
            generation++
            if (m["createSurface"].obj?.get("surfaceId").string != HOME) client.surface(HOME)?.let { remove(HOME) }
        }
        client.handle(m)?.let { err ->
            log("a2ui: rejected: ${err.encode()}")
            scope.launch { link.broadcast(err.encode()) }
        }
        scope.launch { link.broadcast(m.encode()) }
    }

    private fun remove(id: String) = apply(msg("deleteSurface", Json.obj("surfaceId" to Json.str(id))))

    private fun clear() {
        for (s in client.surfaces.toList()) remove(s.id)
    }

    /** [followUp]: a button's answer, which continues the conversation; anything else starts a new one. */
    /**
     * A cancelled answer's request ends at the server only when it next sends
     * a token; one sent before then gets another slot, without the prompt
     * in its cache: minutes of prompt processing on the phone. So the next
     * request waits a little.
     */
    private var serverFree = TimeSource.Monotonic.markNow()

    private fun cancel() {
        if (job?.isActive == true) serverFree = TimeSource.Monotonic.markNow() + 1500.milliseconds
        job?.cancel()
    }

    fun ask(question: String, followUp: Boolean = false) {
        cancel()
        if (!followUp) {
            agent.newTopic()
            this.question = question
        }
        job = scope.launch {
            warm?.join()
            (-serverFree.elapsedNow()).takeIf { it.isPositive() }?.let { delay(it) }
            log("a2ui: asking: $question")
            try {
                agent.ask(question, ::apply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the status line says what went wrong
            }
        }
    }

    fun stop() = cancel()

    /** The start screen, made with A2UI too: how to ask. */
    fun showHome() {
        cancel()
        clear()
        agent.forget()
        question = ""
        val comps = ArrayList<Json>()
        fun c(id: String, type: String, vararg p: Pair<String, Json>) { comps += Json.obj("id" to Json.str(id), "component" to Json.str(type), *p) }
        fun ids(vararg s: String) = Json.Arr(s.map { Json.str(it) })
        c("root", "Column", "children" to ids("mic", "hint", "pc"))
        c("mic", "Row", "children" to ids("mic_icon", "title"), "align" to Json.str("center"), "justify" to Json.str("start"))
        c("mic_icon", "Icon", "name" to Json.str("mic"))
        c("title", "Text", "text" to Json.str("Hold VOL+ and ask"), "variant" to Json.str("h2"))
        c("hint", "Text", "text" to Json.str("Ask about anything. ${agent.model ?: "The model"} picks the screen for the answer and suggests what to ask next; the conversation stays on your subject until Home."), "variant" to Json.str("body"))
        c("pc", "Text", "text" to Json.str("From the PC: echo 'your question' | nc -N 172.16.42.1 $port"), "variant" to Json.str("caption"))
        apply(msg("createSurface", Json.obj("surfaceId" to Json.str(HOME), "catalogId" to Json.str(BASIC_CATALOG))))
        apply(msg("updateComponents", Json.obj("surfaceId" to Json.str(HOME), "components" to Json.Arr(comps))))
    }

    private fun onAction(action: Action, surface: Surface) {
        scope.launch { link.broadcast(action.message(if (surface.sendDataModel) surface.data else null).encode()) }
        log("a2ui: action ${action.name} on ${surface.id} ${action.context.encode()}")
        when (surface.id) {
            LocalAgent.SURFACE -> {
                val q = agent.question(action.name, action.context, surface.data)
                when (action.name) {
                    "back" -> agent.back(::apply)
                    "home" -> showHome()
                    else -> {  // a next question, or a button of the model's
                        if (action.name == "ask") question = action.context["question"].string ?: question
                        ask(q, followUp = true)
                    }
                }
            }
        }
    }

    /** A line from the link: an A2UI message, a question ({"prompt": ...} or plain text) or "home". */
    private fun onLine(line: String) {
        val j = runCatching { Json.parse(line) }.getOrNull()?.obj
        when {
            j != null && j["prompt"] != null -> j["prompt"].string?.let { ask(it) }
            j != null -> apply(j)
            line == "home" -> showHome()
            else -> ask(line)
        }
    }

    companion object {
        const val HOME = "home"
        fun msg(key: String, body: Json.Obj) = Json.obj("version" to Json.str(A2UI_VERSION), key to body)
    }
}

@Composable
internal fun AgentApp(llm: Http, port: Int) {
    val scope = rememberCoroutineScope()
    val app = remember { Controller(scope, llm, port) }
    LaunchedEffect(Unit) { app.start() }
    DisposableEffect(Unit) {
        Device.keyFilter = { code, value -> app.voice.key(code, value) || app.scroll?.key(code, value) == true }
        onDispose { Device.keyFilter = null }
    }
    val agent = app.agent
    MaterialTheme(COLORS) {
        Box(Modifier.fillMaxSize()) {
            key(app.generation) {
                val scroll = remember { FocusScrollState() }
                SideEffect { app.scroll = scroll }
                FocusScroll(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 56.dp), scroll) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), Arrangement.spacedBy(20.dp)) {
                        if (app.question.isNotEmpty() && app.client.surface(Controller.HOME) == null) Question(app.question)
                        when {
                            app.client.surfaces.isNotEmpty() -> for (s in app.client.surfaces) key(s.id) {
                                A2uiView(app.client, s, Modifier.fillMaxWidth(), COLORS)
                            }
                            agent.model == null -> Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                Text("Waiting for a model", style = Typography.headlineSmall)
                                Text("Load one with the Nexus 5X LLM app on the PC. This screen asks the llama.cpp server at ${llm.address}.",
                                    style = Typography.bodyMedium, color = COLORS.onSurfaceVariant)
                            }
                        }
                    }
                }
                ScrollIndicator(scroll, Modifier.fillMaxSize().padding(top = 64.dp, bottom = 56.dp))
            }
            // Drawn after the scrolling part, so it scrolls under them.
            TopAppBar("Ask this phone", subtitle = agent.model ?: "no model",
                navigation = { Icon("auto_awesome", color = COLORS.primary) }) {
                if (agent.busy) IconButton("stop", { app.stop() }, style = ButtonStyle.Tonal)
                if (app.client.surface(Controller.HOME) == null && agent.model != null) IconButton("home", { app.showHome() })
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Row(Modifier.fillMaxWidth().height(56.dp).background(COLORS.surfaceContainer).padding(horizontal = 16.dp),
                    Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                    val status = app.voice.state.ifEmpty { agent.status }
                    when {
                        app.voice.state.startsWith("Listening") -> Icon("mic", color = COLORS.error)
                        agent.busy || app.voice.state.endsWith("...") -> CircularProgressIndicator()
                        status.isEmpty() -> Icon("mic", color = COLORS.onSurfaceVariant)
                    }
                    Text(status.ifEmpty { "Hold VOL+ to ask \u00b7 VOL\u2212 moves \u00b7 POWER presses" }, Modifier.weight(1f),
                        Typography.bodyMedium, if (status.isEmpty()) COLORS.onSurfaceVariant else COLORS.onSurface, maxLines = 1)
                }
            }
        }
    }
}

/** The question asked, as the user's message in a chat. */
@Composable
private fun Question(text: String) {
    Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
        ui.material.Surface(color = COLORS.primaryContainer, contentColor = COLORS.onPrimaryContainer, radius = 20.dp) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}
