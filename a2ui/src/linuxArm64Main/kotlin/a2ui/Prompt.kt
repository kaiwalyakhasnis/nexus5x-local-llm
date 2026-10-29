package a2ui

import ui.a2ui.A2UI_ICONS
import ui.a2ui.Json
import ui.a2ui.arr
import ui.a2ui.obj
import ui.a2ui.string

// What the model is asked for. Any question gets an answer screen: a title,
// content made of components the model picks for the subject, and 2-4 next
// questions on the same subject, which become buttons. The model writes a
// compact form (a nested list of typed components) instead of A2UI itself:
// small models keep nesting straight far better than A2UI's flat list of
// ids, and it costs fewer tokens; Builder (LocalAgent.kt) turns it into real
// A2UI. The server gets SCREEN as a JSON schema, which llama.cpp turns into
// a grammar: the model can only write valid screens, and can't leave the
// next questions out.

/** The icons the model may name: those the renderer draws. */
private val ICONS = A2UI_ICONS.filter { it != "image" }

private val NODE = """{"${'$'}ref":"#/${'$'}defs/node"}"""
private const val BOOL = """{"type":"boolean"}"""
private fun str(max: Int) = """{"type":"string","maxLength":$max}"""
private fun int(min: Int, max: Int) = """{"type":"integer","minimum":$min,"maximum":$max}"""
private fun oneOf(values: List<String>) = """{"enum":[${values.joinToString(",") { "\"$it\"" }}]}"""
private fun list(item: String, min: Int, max: Int) = """{"type":"array","items":$item,"minItems":$min,"maxItems":$max}"""

/** A component: its "type", then [props], all required. */
private fun comp(type: String, vararg props: Pair<String, String>) =
    """{"type":"object","properties":{"type":{"const":"$type"}${props.joinToString("") { ",\"${it.first}\":${it.second}" }}},""" +
        """"required":["type"${props.joinToString("") { ",\"${it.first}\"" }}],"additionalProperties":false}"""

internal val SCREEN: Json = Json.parse(
    """{"type":"object","${'$'}defs":{"node":{"oneOf":[""" + listOf(
        comp("Text", "text" to str(1000), "style" to oneOf(listOf("title", "subtitle", "body", "caption"))),
        comp("Icon", "name" to oneOf(ICONS)),
        comp("Row", "children" to list(NODE, 1, 4)),
        comp("Column", "children" to list(NODE, 1, 8)),
        comp("Card", "children" to list(NODE, 1, 8)),
        comp("Divider"),
        comp("CheckBox", "label" to str(40), "checked" to BOOL),
        comp("Choice", "label" to str(40), "options" to list(str(24), 2, 5)),
        comp("Slider", "label" to str(40), "min" to int(0, 1000), "max" to int(1, 1000), "value" to int(0, 1000)),
    ).joinToString(",") + """]}},"properties":{"title":${str(50)},"content":${list(NODE, 1, 10)},""" +
        """"next":${list(str(60), 2, 4)}},"required":["title","content","next"],"additionalProperties":false}"""
)

internal val SYSTEM = """You answer questions on a small phone screen, as JSON: {"title":..., "content":[components], "next":[questions]}.
Pick the components that fit the answer: Card for each of several things (places, products, options), Text for facts and steps, Row with an Icon for a key value, Choice, CheckBox or Slider when the user should choose or set something (their values come back with the next question).
Components: Text (style title|subtitle|body|caption), Icon (name: ${ICONS.joinToString(", ")}), Row (side by side), Column (one under another), Card (children in a box), Divider, CheckBox (label, checked), Choice (label, options), Slider (label, min, max, value).
"next": 2 to 4 short questions the user is likely to ask next, about the same subject, going deeper. Keep the whole conversation on the subject of the first question.
Answer the question fully: explain, then give the specifics (names, numbers, examples). Use several Text components of 1 to 3 sentences each, not one long one, and finish every sentence. No markdown."""

/** Examples of different kinds of answers, so the model copies the form, not the content. */
internal val EXAMPLES: List<Pair<String, String>> = listOf(
    "plan 3 days in Kyoto" to """{"title":"3 days in Kyoto","content":[{"type":"Card","children":[{"type":"Text","text":"Day 1: East","style":"subtitle"},{"type":"Text","text":"Kiyomizu-dera, Sannenzaka, Gion at dusk.","style":"body"}]},{"type":"Card","children":[{"type":"Text","text":"Day 2: Arashiyama","style":"subtitle"},{"type":"Text","text":"Bamboo grove early, Tenryu-ji, river walk.","style":"body"}]},{"type":"Card","children":[{"type":"Text","text":"Day 3: Fushimi Inari","style":"subtitle"},{"type":"Text","text":"The torii path at 7 am, then Nishiki Market.","style":"body"}]},{"type":"Choice","label":"Budget","options":["Low","Medium","High"]}],"next":["Where should I stay?","What should I eat each day?","How do I get around?"]}""",
    "Where should I stay? Values: {\"Budget\":\"Low\"}" to """{"title":"Cheap places to stay","content":[{"type":"Text","text":"Stay near Kyoto Station or Gion: buses go everywhere from both.","style":"body"},{"type":"Card","children":[{"type":"Text","text":"Piece Hostel Sanjo","style":"subtitle"},{"type":"Text","text":"Dorms from 3,000 yen, central.","style":"caption"}]},{"type":"Card","children":[{"type":"Text","text":"K's House Kyoto","style":"subtitle"},{"type":"Text","text":"Private rooms from 7,000 yen, near the station.","style":"caption"}]}],"next":["Is a ryokan worth it?","How early should I book?","Which area is quietest?"]}""",
    "what is DNS" to """{"title":"DNS: the internet's phone book","content":[{"type":"Text","text":"DNS (Domain Name System) turns names like example.com into IP addresses, which computers use to reach each other.","style":"body"},{"type":"Text","text":"When you open a site, your device asks a resolver, usually your router or ISP. The resolver asks the root servers, then the .com servers, then the site's own name server, and caches the answer.","style":"body"},{"type":"Card","children":[{"type":"Text","text":"A record","style":"subtitle"},{"type":"Text","text":"Name to IPv4 address, e.g. 93.184.216.34.","style":"caption"}]},{"type":"Card","children":[{"type":"Text","text":"MX record","style":"subtitle"},{"type":"Text","text":"Which server takes the domain's email.","style":"caption"}]}],"next":["How does DNS caching work?","What is DNS over HTTPS?","How do I change my DNS server?"]}""",
    "how do I make pancakes" to """{"title":"Pancakes","content":[{"type":"Slider","label":"Servings","min":1,"max":6,"value":2},{"type":"Text","text":"1. Whisk 150 g flour, 1 tsp baking powder and a pinch of salt.","style":"body"},{"type":"Text","text":"2. Add 1 egg and 200 ml milk; stir until smooth.","style":"body"},{"type":"Text","text":"3. Cook ladlefuls on a hot buttered pan, 1-2 min a side.","style":"body"},{"type":"Row","children":[{"type":"Icon","name":"schedule"},{"type":"Text","text":"15 minutes","style":"subtitle"}]}],"next":["Can I make them without eggs?","What toppings go well?","How do I keep them fluffy?"]}""",
)

// Compact nodes for the parts the app adds around the model's content.
private fun text(t: String?, style: String) = t?.let { Json.obj("type" to Json.str("Text"), "text" to Json.str(it), "style" to Json.str(style)) }
private fun button(label: String, action: String, primary: Boolean, context: Json.Obj = Json.obj()) = Json.obj(
    "type" to Json.str("Button"), "label" to Json.str(label), "action" to Json.str(action),
    "primary" to Json.Bool(primary), "context" to context,
)

/**
 * The screen for what the model has written so far ([o] may be cut off:
 * parts appear when whole): the title, the content, then the next
 * questions as buttons ("ask"), and Back when there is a screen to go back to.
 */
internal fun screen(o: Json.Obj, canGoBack: Boolean): List<Json> {
    val next = o["next"].arr.orEmpty().mapNotNull { it.string?.takeIf { s -> s.isNotBlank() } }
        .takeIf { o.complete || o["next"] != null }.orEmpty()
    return listOfNotNull(text(o["title"].string, "title")) +
        o["content"].arr.orEmpty() +
        (if (next.isNotEmpty()) listOfNotNull(text("Ask next", "caption")) else emptyList()) +
        next.map { button(it, "ask", false, Json.obj("question" to Json.str(it))) } +
        (if (o.complete && canGoBack) listOf(button("Back", "back", false)) else emptyList())
}
