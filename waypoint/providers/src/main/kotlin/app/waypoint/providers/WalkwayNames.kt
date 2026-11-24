package app.waypoint.providers

import app.waypoint.trip.LatLng
import app.waypoint.trip.Polyline
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Walking routes in towns often follow pavements mapped as their own, unnamed paths, so the
 * instructions say "Turn left onto the walkway" while you're walking along a street. This puts the
 * street's name in.
 *
 * For each unnamed step, the nearest road (Valhalla's locate, roads only) within [MAX_DISTANCE_M]
 * that runs alongside it (within [MAX_ANGLE] degrees) names it: in the step's name, its own
 * instruction, and the voice and banner instructions that announce it. Paths away from roads
 * (parks, between buildings) and crossings keep "the walkway".
 */
object WalkwayNames {
    const val MAX_DISTANCE_M = 20.0
    const val MAX_ANGLE = 30.0
    /** Shorter steps are links and crossings, not walks along a street. */
    private const val MIN_STEP_M = 8.0
    private val UNNAMED = Regex("""\bthe (walkway|sidewalk|footway|pavement)\b""", RegexOption.IGNORE_CASE)

    data class Road(val name: String, val distance: Double, val heading: Double)

    private data class Probe(val route: Int, val leg: Int, val step: Int, val at: LatLng, val bearing: Double)

    /**
     * [osrmJson] with unnamed steps named after the street they run along. [locate] gives, for
     * each point, the roads near it. Unchanged when nothing could be named.
     */
    suspend fun name(osrmJson: String, locate: suspend (List<LatLng>) -> List<List<Road>>): String {
        val root = lenientJson.parseToJsonElement(osrmJson).jsonObject
        val probes = probes(root)
        if (probes.isEmpty()) return osrmJson
        val near = locate(probes.map { it.at })
        val names = probes.mapIndexedNotNull { k, p ->
            near.getOrNull(k).orEmpty()
                .filter { it.distance <= MAX_DISTANCE_M && alongside(p.bearing, it.heading) }
                .minByOrNull { it.distance }
                ?.let { Triple(p.route, p.leg, p.step) to it.name }
        }.toMap()
        if (names.isEmpty()) return osrmJson
        return rename(root, names).toString()
    }

    /** The roads near each point, from a Valhalla locate response. */
    fun parseLocate(root: JsonElement): List<List<Road>> = root.arr.map { loc ->
        loc.obj?.get("edges").arr.mapNotNull { e ->
            val edge = e.obj ?: return@mapNotNull null
            val name = edge["edge_info"].obj?.get("names").arr.firstOrNull().str ?: return@mapNotNull null
            Road(name, edge["distance"].num ?: return@mapNotNull null, edge["heading"].num ?: return@mapNotNull null)
        }
    }

    private fun probes(root: JsonObject): List<Probe> = buildList {
        root["routes"].arr.forEachIndexed { r, route ->
            route.obj?.get("legs").arr.forEachIndexed { l, leg ->
                leg.obj?.get("steps").arr.forEachIndexed { s, step ->
                    val o = step.obj ?: return@forEachIndexed
                    if (!o["name"].str.isNullOrBlank()) return@forEachIndexed
                    if ((o["distance"].num ?: 0.0) < MIN_STEP_M) return@forEachIndexed
                    if (o["maneuver"].obj?.get("type").str == "arrive") return@forEachIndexed
                    val pts = o["geometry"].str?.let { Polyline.decode(it) } ?: return@forEachIndexed
                    if (pts.size < 2) return@forEachIndexed
                    val mid = pts.size / 2
                    val a = pts[(mid - 1).coerceAtLeast(0)]
                    val b = pts[(mid + 1).coerceAtMost(pts.lastIndex)].takeIf { it != a } ?: pts.last()
                    add(Probe(r, l, s, pts[mid], bearing(a, b)))
                }
            }
        }
    }

    private fun bearing(a: LatLng, b: LatLng): Double {
        val dx = (b.lng - a.lng) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    /** Parallel either way round (a road's heading may point against your direction). */
    private fun alongside(a: Double, b: Double): Boolean {
        val d = abs(a - b) % 180
        return minOf(d, 180 - d) <= MAX_ANGLE
    }

    private fun rename(root: JsonObject, names: Map<Triple<Int, Int, Int>, String>): JsonObject =
        root.with("routes", JsonArray(root["routes"].arr.mapIndexed { r, route ->
            val ro = route.jsonObject
            ro.with("legs", JsonArray(ro["legs"].arr.mapIndexed { l, leg ->
                val lo = leg.jsonObject
                val steps = lo["steps"].arr
                lo.with("steps", JsonArray(steps.mapIndexed { s, step -> renameStep(step.jsonObject, s) { k -> names[Triple(r, l, k)] } }))
            }))
        }))

    /**
     * Step [i]'s own name and instruction are about step [i]; its voice and banner instructions
     * announce the next step (or, for the first step, start with its own instruction), and a
     * "Then …" sentence the one after.
     */
    private fun renameStep(step: JsonObject, i: Int, nameOf: (Int) -> String?): JsonObject {
        var out = step
        nameOf(i)?.let { out = out.with("name", JsonPrimitive(it)) }
        val maneuver = step["maneuver"].obj
        val own = maneuver?.get("instruction").str
        if (maneuver != null && own != null) {
            out = out.with("maneuver", maneuver.with("instruction", JsonPrimitive(retarget(own, i, nameOf, xml = false))))
        }
        fun first(text: String?) = if (own != null && text != null && text.startsWith(own.trim())) i else i + 1

        step["voiceInstructions"]?.let { list ->
            out = out.with("voiceInstructions", JsonArray(list.arr.map { v ->
                val o = v.jsonObject
                val start = first(o["announcement"].str)
                var nv = o
                o["announcement"].str?.let { nv = nv.with("announcement", JsonPrimitive(retarget(it, start, nameOf, xml = false))) }
                o["ssmlAnnouncement"].str?.let { nv = nv.with("ssmlAnnouncement", JsonPrimitive(retarget(it, start, nameOf, xml = true))) }
                nv
            }))
        }
        step["bannerInstructions"]?.let { list ->
            out = out.with("bannerInstructions", JsonArray(list.arr.map { b ->
                val o = b.jsonObject
                val start = first(o["primary"].obj?.get("text").str)
                var nb = o
                for (part in listOf("primary", "secondary", "sub")) {
                    val content = o[part].obj ?: continue
                    var nc = content
                    content["text"].str?.let { nc = nc.with("text", JsonPrimitive(retarget(it, start, nameOf, xml = false))) }
                    content["components"]?.let { comps ->
                        nc = nc.with("components", JsonArray(comps.arr.map { c ->
                            val co = c.jsonObject
                            co["text"].str?.let { co.with("text", JsonPrimitive(retarget(it, start, nameOf, xml = false))) } ?: co
                        }))
                    }
                    nb = nb.with(part, nc)
                }
                nb
            }))
        }
        return out
    }

    /** Replaces "the walkway" in each sentence of [text] with the name of the step it's about. */
    private fun retarget(text: String, first: Int, nameOf: (Int) -> String?, xml: Boolean): String {
        if (!UNNAMED.containsMatchIn(text)) return text
        val sentences = text.split(Regex("""(?<=\.)\s+"""))
        return sentences.mapIndexed { k, sentence ->
            val name = nameOf(first + k) ?: return@mapIndexed sentence
            UNNAMED.replace(sentence) { if (xml) escapeXml(name) else name }
        }.joinToString(" ")
    }

    private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
}
