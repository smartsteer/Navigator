package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kontur: nagrywamy przejazdy jako pasy punktów, a prowadzenie idzie wzdłuż najbliższego
 * wcześniejszego pasa — przesuniętego o szerokość maszyny w stronę ciągnika
 * albo dokładnie po nim, gdy ciągnik jest bliżej niż pół szerokości.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CContour.cs
 * (StartContourLine, AddPoint, StopContourLine, BuildContourGuidanceLine) i SourceCode/GPS/Forms/Position.designer.cs (AddContourPoints).
 */
class Contour {
    val strips = ArrayList<MutableList<Pose>>()
    private var current: MutableList<Pose>? = null

    /** Pas, po którym prowadzimy, i miejsce na nim. */
    private var stripNum = -1
    private var lastLockPt = 0
    var locked = false; private set
    /** Bieżąca linia prowadzenia konturu (w kierunku pasa odniesienia). */
    var guide: List<Pose> = emptyList(); private set
    /** Czy jedziemy zgodnie z kierunkiem pasa odniesienia. */
    var sameWay = true; private set

    val isRecording: Boolean get() = current != null

    /** Dodaje punkt nagrywanego pasa (gdy praca trwa), co najmniej 0,5 m od poprzedniego. */
    fun record(pose: Pose, working: Boolean) {
        if (!working) { stop(); return }
        val c = current ?: ArrayList<Pose>(64).also { current = it; strips.add(it) }
        if (c.isEmpty() || Curves.d2(c.last().p, pose.p) >= 0.25) c.add(pose)
    }

    /** Koniec pasa; krótkie pasy (≤ 5 punktów) są wyrzucane. */
    fun stop() {
        val c = current ?: return
        if (c.size <= 5) strips.remove(c)
        current = null
    }

    fun clear() {
        strips.clear(); current = null; guide = emptyList(); locked = false; stripNum = -1
    }

    /**
     * Buduje linię prowadzenia przy ciągniku. [hold] = autoster włączony: trzymamy się wybranego pasa.
     */
    fun build(pivot: Vec2, heading: Double, toolWidth: Double, overlap: Double, hold: Boolean): List<Pose> {
        val spacing = (toolWidth - overlap).coerceAtLeast(0.1)
        val halfWidth = toolWidth / 2
        val toolContourDistance = toolWidth * 3
        if (strips.isEmpty()) return reset()

        val sinH = sin(heading) * 0.2
        val cosH = cos(heading) * 0.2
        val sin2HL = sin(heading + PI / 2)
        val cos2HL = cos(heading + PI / 2)
        val boxA = Vec2(pivot.x - sin2HL + sinH, pivot.y - cos2HL + cosH)
        val boxB = Vec2(pivot.x + sin2HL + sinH, pivot.y + cos2HL + cosH)

        var pt: Int
        var minDistance = Double.MAX_VALUE
        if (!(hold && locked && stripNum in strips.indices)) {
            stripNum = -1
            pt = 0
            for (s in strips.indices) {
                val strip = strips[s]
                var p = 0
                while (p < strip.size) {
                    val q = strip[p].p
                    // pomijamy punkty za ciągnikiem (w tym świeżo nagrany pas)
                    if (((boxA.x - boxB.x) * (q.y - boxB.y)) - ((boxA.y - boxB.y) * (q.x - boxB.x)) > 0) { p += 3; continue }
                    val d = Curves.d2(pivot, q)
                    if (d < minDistance) { minDistance = d; stripNum = s; pt = p; lastLockPt = p }
                    p += 3
                }
            }
            minDistance = sqrt(minDistance)
            if (stripNum < 0 || minDistance > toolContourDistance || strips[stripNum].size < 4) return reset()
        } else {
            val strip = strips[stripNum]
            if (strip.size < 2) return reset()
            val start = (lastLockPt - 20).coerceAtLeast(0)
            val stop = (lastLockPt + 20).coerceAtMost(strip.size)
            pt = lastLockPt
            var i = start
            while (i < stop) {
                val d = Curves.d2(pivot, strip[i].p)
                if (minDistance >= d) { minDistance = d; pt = i; lastLockPt = i }
                i += 3
            }
            if (sqrt(minDistance) > toolContourDistance) return reset()
        }

        val strip = strips[stripNum]
        val ref = strip[pt].p
        val distanceFromRefLine = when {
            pt < strip.size - 1 -> signedDistance(ref, strip[pt + 1].p, pivot)
            pt > 0 -> signedDistance(strip[pt - 1].p, ref, pivot)
            else -> return reset()
        }
        sameWay = PI - abs(abs(heading - strip[pt].heading) - PI) < 1.57
        val refDist = distanceFromRefLine / spacing
        val howManyPathsAway = if (abs(distanceFromRefLine) > halfWidth) (if (refDist < 0) -1 else 1) else 0

        val start: Int
        val stop: Int
        if (sameWay) {
            start = (pt - 20).coerceAtLeast(0); stop = (pt + 70).coerceAtMost(strip.size)
        } else {
            start = (pt - 70).coerceAtLeast(0); stop = (pt + 20).coerceAtMost(strip.size)
        }
        val distAway = spacing * howManyPathsAway
        val distSqAway = distAway * distAway * 0.97
        val out = ArrayList<Vec2>()
        for (i in start until stop) {
            val s = strip[i]
            val point = Vec2(s.p.x + cos(s.heading) * distAway, s.p.y - sin(s.heading) * distAway)
            var ok = true
            for (j in start until stop) if (Curves.d2(point, strip[j].p) < distSqAway) { ok = false; break }
            if (!ok) continue
            if (out.isEmpty() || Curves.d2(point, out.last()) > 0.2) out.add(point)
        }
        if (out.size < 5) return reset()
        locked = true
        val g = Curves.headings(out)
        guide = if (sameWay) g else Curves.reversed(g)
        return guide
    }

    private fun reset(): List<Pose> {
        guide = emptyList()
        locked = false
        return guide
    }

    /** Odległość punktu od prostej a→b: + = na prawo. */
    private fun signedDistance(a: Vec2, b: Vec2, p: Vec2): Double {
        val dx = b.x - a.x
        val dz = b.y - a.y
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-12) return 0.0
        return ((dz * p.x) - (dx * p.y) + (b.x * a.y) - (b.y * a.x)) / len
    }
}
