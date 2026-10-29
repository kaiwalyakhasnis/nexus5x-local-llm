package a2ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ui.a2ui.A2UI_VERSION
import ui.a2ui.BASIC_CATALOG
import ui.a2ui.Json
import ui.a2ui.arr
import ui.a2ui.bool
import ui.a2ui.double
import ui.a2ui.encode
import ui.a2ui.obj
import ui.a2ui.string
import ui.log
import kotlin.time.TimeSource

private val clock = TimeSource.Monotonic.markNow()
private fun nowNanos() = clock.elapsedNow().inWholeNanoseconds

/**
 * The agent half of A2UI, on the phone: asks a llama.cpp server (its
 * OpenAI-style chat API) for a screen, and sends what the model writes to
 * the renderer as A2UI messages while it is being written. Button presses
 * come back as the next question, with the values of the screen's controls.
 */
internal class LocalAgent(private val http: Http) {
    /** The loaded model's name, or null while the server has none. */
    var model by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    private val history = ArrayList<Pair<String, String>>()  // (question, the model's screen)
    private var labels = mapOf<String, String>()              // data key -> control label, for the answers
    private var surfaceShown = false
    /** The screens shown so far in this conversation (components, data, labels), for Back. */
    private class Shown(val components: Json.Arr, val data: Json.Obj, val labels: Map<String, String>)
    private val shown = ArrayList<Shown>()

    /** Asks the server which model it serves. */
    suspend fun poll() {
        val name = try {
            val sb = StringBuilder()
            val code = http.request("GET", "/v1/models") { sb.append(it) }
            if (code == 200) Json.parse(sb.toString()).obj?.get("data")?.arr?.firstOrNull()?.obj?.get("id").string else null
        } catch (e: Exception) {
            null
        }
        if (name != model) {
            log("a2ui: model ${name ?: "(none)"}")
            model = name
            history.clear()
        }
    }

    /**
     * Has the server read the instructions and examples once: llama.cpp
     * keeps them in its cache, so the first real question doesn't wait for
     * them (about half a minute on the phone).
     */
    suspend fun warmUp() {
        busy = true
        status = "Reading the instructions (once per model)"
        val t0 = nowNanos()
        try {
            chat(messages("Hello"), SCREEN, maxTokens = 1) {}
            log("a2ui: warm-up ${(nowNanos() - t0) / 1_000_000} ms")
            status = ""
        } catch (e: Exception) {
            status = "The model server did not answer"
            log("a2ui: warm-up failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /**
     * A Button was pressed on our surface: the next question for the model.
     * A next question is asked as itself; any other button says what it was.
     * Both carry the values of the screen's controls.
     */
    fun question(action: String, context: Json.Obj, data: Json?): String {
        val values = LinkedHashMap<String, Json>()
        (data as? Json.Obj)?.fields?.forEach { (k, v) ->
            val label = labels[k] ?: k
            values[label] = (v as? Json.Arr)?.items?.let { if (it.size == 1) it[0] else v } ?: v
        }
        val asked = context["question"].string ?: ("Pressed \"$action\"" + if (context.fields.isNotEmpty()) " on ${context.encode()}" else "")
        return asked + if (values.isNotEmpty()) " Values: ${Json.Obj(values).encode()}" else ""
    }

    /** Back: the screen before this one again, as it was (the model isn't asked). False when there is none. */
    fun back(emit: (Json.Obj) -> Unit): Boolean {
        if (shown.size < 2) return false
        shown.removeAt(shown.size - 1)
        if (history.isNotEmpty()) history.removeAt(history.size - 1)
        val s = shown.last()
        if (surfaceShown) emit(msg("deleteSurface", Json.obj("surfaceId" to Json.str(SURFACE))))
        emit(msg("createSurface", Json.obj("surfaceId" to Json.str(SURFACE), "catalogId" to Json.str(BASIC_CATALOG), "sendDataModel" to Json.Bool(true))))
        emit(msg("updateComponents", Json.obj("surfaceId" to Json.str(SURFACE), "components" to s.components)))
        emit(msg("updateDataModel", Json.obj("surfaceId" to Json.str(SURFACE), "path" to Json.str("/"), "value" to s.data)))
        surfaceShown = true
        labels = s.labels
        return true
    }

    /** Asks for the answer screen to [question]; [emit] gets the A2UI messages for it. */
    suspend fun ask(question: String, emit: (Json.Obj) -> Unit) {
        busy = true
        status = "Thinking"
        val t0 = nowNanos()
        var tFirst = 0L
        var tokens = 0
        val text = StringBuilder()
        var lastComponents = ""
        var started = false
        val seenData = HashSet<String>()
        var built = Builder()
        var closed: Json.Obj? = null  // the reply, closed, if the token budget cut it off
        fun update(final: Boolean) {
            var reply = Json.parsePrefix(text.toString())?.obj ?: return
            if (final && !reply.complete) reply = closeCut(reply).also { closed = it }
            built = Builder().apply { build(screen(reply, shown.isNotEmpty())) }
            if (built.components.size <= 1 && !final) return  // only the root yet
            if (!started) {
                started = true
                if (surfaceShown) emit(msg("deleteSurface", Json.obj("surfaceId" to Json.str(SURFACE))))
                emit(msg("createSurface", Json.obj("surfaceId" to Json.str(SURFACE), "catalogId" to Json.str(BASIC_CATALOG), "sendDataModel" to Json.Bool(true))))
                surfaceShown = true
            }
            for ((k, v) in built.data) if (seenData.add(k)) {
                emit(msg("updateDataModel", Json.obj("surfaceId" to Json.str(SURFACE), "path" to Json.str("/$k"), "value" to v)))
            }
            val comps = Json.Arr(built.components)
            val encoded = comps.encode()
            if (encoded != lastComponents) {
                lastComponents = encoded
                emit(msg("updateComponents", Json.obj("surfaceId" to Json.str(SURFACE), "components" to comps)))
            }
            labels = built.labels
        }
        try {
            chat(messages(question), SCREEN, maxTokens = MAX_TOKENS) { piece ->
                if (tokens == 0) tFirst = nowNanos()
                tokens++
                text.append(piece)
                val secs = (nowNanos() - tFirst) / 1e9
                status = if (secs > 0.5) "Writing: $tokens tokens, ${(tokens / secs * 10).toInt() / 10.0} tok/s" else "Writing"
                update(false)
            }
            update(true)
            if (!started) {  // nothing usable: say so on a screen of our own
                val err = Builder().apply { build(listOf(Json.obj("type" to Json.str("Text"), "text" to Json.str("The model wrote no screen. Try again."), "style" to Json.str("body")))) }
                if (surfaceShown) emit(msg("deleteSurface", Json.obj("surfaceId" to Json.str(SURFACE))))
                emit(msg("createSurface", Json.obj("surfaceId" to Json.str(SURFACE), "catalogId" to Json.str(BASIC_CATALOG))))
                emit(msg("updateComponents", Json.obj("surfaceId" to Json.str(SURFACE), "components" to Json.Arr(err.components))))
                surfaceShown = true
            }
            // The conversation: its first exchange (the subject) and the last three.
            history += question to (closed?.encode() ?: text.toString())
            while (history.size > 4) history.removeAt(1)
            if (started) shown += Shown(Json.Arr(built.components), Json.Obj(built.data), built.labels)
            val ms = (nowNanos() - t0) / 1_000_000
            log("a2ui: $tokens tokens in $ms ms: ${text.take(400)}")
            status = "$tokens tokens in ${ms / 100 / 10.0} s" + if (closed != null) " (cut: too long)" else ""
        } catch (e: Exception) {
            status = "Failed: ${e.message}"
            log("a2ui: request failed: ${e.message}")
            throw e
        } finally {
            busy = false
        }
    }

    /**
     * A reply the token budget cut off, closed: what is whole of it, a note
     * that it was cut, and its next questions (or ways to go on).
     */
    private fun closeCut(o: Json.Obj): Json.Obj {
        fun close(n: Json): Json? {
            val c = n.obj ?: return null
            if (c.complete) return c
            val kids = c["children"].arr?.mapNotNull(::close)?.takeIf { it.isNotEmpty() } ?: return null
            return Json.obj("type" to (c["type"] ?: return null), "children" to Json.Arr(kids))
        }
        val note = Json.obj("type" to Json.str("Text"), "text" to Json.str("The answer was cut here: it got too long."), "style" to Json.str("caption"))
        val next = o["next"].arr.orEmpty().filter { !it.string.isNullOrBlank() }
            .ifEmpty { listOf(Json.str("Continue the answer"), Json.str("Sum it up briefly")) }
        return Json.obj(
            "title" to Json.str(o["title"].string ?: "Answer"),
            "content" to Json.Arr(o["content"].arr.orEmpty().mapNotNull(::close) + note),
            "next" to Json.Arr(next),
        )
    }

    /** A new subject: the next question doesn't see the earlier ones (shorter to read, too). */
    fun newTopic() {
        history.clear()
        shown.clear()
    }

    fun forget() {
        history.clear()
        shown.clear()
        surfaceShown = false
        status = ""
    }

    private fun messages(question: String): Json.Arr {
        val m = ArrayList<Json>()
        fun add(role: String, content: String) { m += Json.obj("role" to Json.str(role), "content" to Json.str(content)) }
        add("system", SYSTEM)
        for ((q, a) in EXAMPLES) { add("user", q); add("assistant", a) }
        for ((q, a) in history) { add("user", q); add("assistant", a) }
        add("user", question)
        return Json.Arr(m)
    }

    /** One streamed chat completion, constrained to [schema]; [onPiece] gets the text as it comes. */
    private suspend fun chat(messages: Json.Arr, schema: Json, maxTokens: Int, onPiece: (String) -> Unit) {
        val body = Json.obj(
            "messages" to messages,
            "stream" to Json.Bool(true),
            "max_tokens" to Json.num(maxTokens),
            "temperature" to Json.num(0.3),
            "cache_prompt" to Json.Bool(true),
            "response_format" to Json.obj("type" to Json.str("json_schema"), "json_schema" to Json.obj("schema" to schema)),
            "chat_template_kwargs" to Json.obj("enable_thinking" to Json.Bool(false)),  // Qwen3 & co.: answer directly
        ).encode()
        val errorText = StringBuilder()
        val code = http.request("POST", "/v1/chat/completions", body) { line ->
            if (!line.startsWith("data: ")) { errorText.append(line); return@request }
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") return@request
            val delta = Json.parsePrefix(data)?.obj?.get("choices")?.arr?.firstOrNull()?.obj?.get("delta")?.obj
            delta?.get("content").string?.let { if (it.isNotEmpty()) onPiece(it) }
        }
        if (code != 200) throw NetException("server said $code ${errorText.take(200)}")
    }

    companion object {
        const val SURFACE = "local"
        /** A safety stop, not a size: a whole answer of 10 parts fits (at ~3 tokens/s on the phone, ~10 min). */
        private const val MAX_TOKENS = 2000
        private fun msg(key: String, body: Json.Obj) = Json.obj("version" to Json.str(A2UI_VERSION), key to body)
    }
}

/** JSON a model meant to close a string with, kept as text: a quote (often a curly one), then } ] ) , { */
private val JSON_TAIL = Regex("[\"\u201d]\\s*[}\\])]+[\\s,{\\[]*$")

/**
 * The model's compact screen as A2UI components: ids from each component's
 * place in the screen (so they stay the same while the screen is still
 * being written), controls bound to the data model, a "root" Column on top.
 */
private class Builder {
    val components = ArrayList<Json>()
    val data = LinkedHashMap<String, Json>()
    val labels = LinkedHashMap<String, String>()
    private var buttons = 0

    fun build(screen: List<Json>) {
        buttons = screen.count { it.obj?.get("type").string == "Button" }
        val ids = screen.mapIndexedNotNull { i, n -> node(n, "c$i") }
        components.add(0, comp("root", "Column", "children" to Json.Arr(ids.map { Json.str(it) })))
    }

    /** A string of the model's, without what small models leave in them: quotes and commas meant to close the string, HTML entities. */
    private fun text(o: Json.Obj, key: String): String? = o[key].string?.let { clean(it) }?.ifEmpty { null }

    private fun clean(s: String) = s.replace("\",\"", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(JSON_TAIL, "").trim().trimEnd('"', ',', ' ').trimStart('"', ' ')

    private fun comp(id: String, type: String, vararg props: Pair<String, Json?>) =
        Json.obj("id" to Json.str(id), "component" to Json.str(type), *props)

    /** Adds [n] (and what it contains); returns its id, or null while it is unfinished. */
    private fun node(n: Json, id: String): String? {
        val o = n.obj ?: return null
        val type = o["type"].string ?: return null
        if (!o.complete && type != "Row" && type != "Column" && type != "Card") return null  // leaves appear when whole
        when (type) {
            "Text" -> {
                val variant = when (o["style"].string) {
                    "title" -> "h2"
                    "subtitle" -> "h4"
                    "caption" -> "caption"
                    else -> "body"
                }
                components += comp(id, "Text", "text" to Json.str(text(o, "text") ?: return null), "variant" to Json.str(variant))
            }
            "Icon" -> components += comp(id, "Icon", "name" to Json.str(o["name"].string ?: return null))
            "Divider" -> components += comp(id, "Divider")
            "Row", "Column", "Card" -> {
                val kids = o["children"].arr.orEmpty().mapIndexedNotNull { i, c -> node(c, "${id}_$i") }
                if (kids.isEmpty()) return null
                val list = Json.Arr(kids.map { Json.str(it) })
                if (type == "Row") components += comp(id, "Row", "children" to list, "align" to Json.str("center"))
                else if (type == "Column") components += comp(id, "Column", "children" to list)
                else {
                    components += comp(id, "Card", "child" to Json.str("${id}_col"))
                    components += comp("${id}_col", "Column", "children" to list)
                }
            }
            "Button" -> {
                val label = text(o, "label") ?: return null
                components += comp("${id}_label", "Text", "text" to Json.str(label))
                val primary = o["primary"].bool ?: (buttons <= 1)
                components += comp(id, "Button", "child" to Json.str("${id}_label"),
                    "variant" to Json.str(if (primary) "primary" else "default"),
                    "action" to Json.obj("event" to Json.obj("name" to Json.str(o["action"].string ?: label), "context" to (o["context"].obj ?: Json.obj()))))
            }
            "CheckBox" -> {
                val label = text(o, "label") ?: return null
                components += comp(id, "CheckBox", "label" to Json.str(label), "value" to Json.obj("path" to Json.str("/$id")))
                data[id] = Json.Bool(o["checked"].bool ?: false)
                labels[id] = label
            }
            "Choice" -> {
                val label = text(o, "label") ?: return null
                val options = o["options"].arr.orEmpty().mapNotNull { it.string?.let(::clean)?.ifEmpty { null } }
                components += comp("${id}_label", "Text", "text" to Json.str(label), "variant" to Json.str("caption"))
                components += comp("${id}_pick", "ChoicePicker", "variant" to Json.str("mutuallyExclusive"),
                    "options" to Json.Arr(options.map { Json.obj("label" to Json.str(it), "value" to Json.str(it)) }),
                    "value" to Json.obj("path" to Json.str("/$id")))
                components += comp(id, "Column", "children" to Json.Arr(listOf(Json.str("${id}_label"), Json.str("${id}_pick"))))
                data[id] = Json.Arr(emptyList())
                labels[id] = label
            }
            "Slider" -> {
                val label = text(o, "label") ?: return null
                val min = o["min"].double ?: 0.0
                val max = maxOf(o["max"].double ?: 10.0, min + 1)
                components += comp(id, "Slider", "label" to Json.str(label), "min" to Json.num(min), "max" to Json.num(max),
                    "value" to Json.obj("path" to Json.str("/$id")))
                data[id] = Json.num((o["value"].double ?: min).coerceIn(min, max))
                labels[id] = label
            }
            else -> return null
        }
        return id
    }
}
