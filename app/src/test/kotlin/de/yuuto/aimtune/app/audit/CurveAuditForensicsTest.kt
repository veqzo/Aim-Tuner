package de.yuuto.aimtune.app.audit

import de.yuuto.aimtune.app.RoundProcessor
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.accel.LastSearch
import de.yuuto.aimtune.app.accel.PriorCalibration
import de.yuuto.aimtune.app.accel.StoredSearches
import de.yuuto.aimtune.app.blind.BlindTest
import de.yuuto.aimtune.core.accel.AbCondition
import de.yuuto.aimtune.core.accel.AbOutcome
import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.AccelCurve
import de.yuuto.aimtune.core.accel.AccelProcessor
import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.accel.NoAccel
import de.yuuto.aimtune.core.accel.boundedRecommendation
import de.yuuto.aimtune.core.accel.evaluateAb
import de.yuuto.aimtune.core.accel.fitAccel
import de.yuuto.aimtune.core.metrics.MovementMetric
import de.yuuto.aimtune.core.metrics.RoundMetrics
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.RoundScorer
import de.yuuto.aimtune.core.score.ScoreCalculator
import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.core.units.cm360
import de.yuuto.aimtune.core.units.searchRange
import de.yuuto.aimtune.core.units.sensitivityFor
import de.yuuto.aimtune.storage.Database
import de.yuuto.aimtune.storage.RoundRepository
import de.yuuto.aimtune.storage.RoundRow
import de.yuuto.aimtune.storage.SessionKind
import de.yuuto.aimtune.storage.SessionRow
import de.yuuto.aimtune.storage.SettingsStore
import de.yuuto.aimtune.storage.scoreInputs
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Throwaway forensic report on the acceleration (curve finder) data of a COPY of the real database (env AIMTUNE_REAL_DB).
 * Reads settings.json next to the database for the metric weights. Writes build/reports/audit/forensics.txt.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REAL_DB", matches = ".+")
class CurveAuditForensicsTest {
    /** One stored round with its recording, metrics and decoded curve. */
    private class Rd(val row: RoundRow, val session: SessionRow, val rec: RoundRecording?, val metrics: RoundMetrics?) {
        val curve: AccelCurve? = row.accel?.let { runCatching { AccelSpec.decode(it) }.getOrNull() }
        val plain: Boolean get() = row.accel == null || curve === NoAccel
        val strength: Double? get() = StoredSearches.strengthOf(row.accel)
        val moves: List<MovementMetric> get() = metrics?.movements.orEmpty()
    }

    /** One first movement: hand speed in in/s, target distance in degrees, signed first error ratio. */
    private class Mv(val speed: Double, val dist: Double, val err: Double, val round: Rd) {
        val used: Boolean get() = speed >= AccelAnalysis.MIN_SPEED && (1.0 + err) in AccelAnalysis.MIN_RATIO..AccelAnalysis.MAX_RATIO
    }

    private val out = StringBuilder()
    private fun line(s: String = "") { out.appendLine(s) }
    private fun f(x: Double?, d: Int = 2): String = if (x == null || x.isNaN()) "nan" else String.format(Locale.ROOT, "%.${d}f", x)

    private lateinit var repo: RoundRepository
    private lateinit var processor: RoundProcessor
    private lateinit var weights: MetricWeights
    private lateinit var sessions: List<SessionRow>
    private lateinit var rds: List<Rd>
    private lateinit var byId: Map<Long, Rd>
    private lateinit var scores: Map<Long, Double?>
    private lateinit var searchRds: List<Rd>

    // ---------- stats helpers ----------
    private fun mean(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.average()
    private fun q(v: List<Double>, p: Double): Double { if (v.isEmpty()) return Double.NaN; val s = v.sorted(); return s[((s.size - 1) * p).toInt()] }
    private fun median(v: List<Double>) = q(v, 0.5)
    private fun pearson(x: List<Double>, y: List<Double>): Double {
        if (x.size < 3) return Double.NaN
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx).pow(2); syy += (y[i] - my).pow(2) }
        return if (sxx == 0.0 || syy == 0.0) Double.NaN else sxy / sqrt(sxx * syy)
    }
    private fun lin(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        if (x.size < 2) return Double.NaN to Double.NaN
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx).pow(2) }
        val s = if (sxx == 0.0) Double.NaN else sxy / sxx
        return s to (my - s * mx)
    }

    /** Quadratic least squares y = a0 + a1 (x - m) + a2 (x - m)^2; returns [a0, a1, a2, m]. */
    private fun quad(x: List<Double>, y: List<Double>): DoubleArray? {
        if (x.size < 5) return null
        val m = x.average(); val xs = x.map { it - m }
        val s = DoubleArray(5) { k -> xs.sumOf { it.pow(k) } }
        val t = DoubleArray(3) { k -> xs.indices.sumOf { xs[it].pow(k) * y[it] } }
        val a = arrayOf(doubleArrayOf(s[0], s[1], s[2], t[0]), doubleArrayOf(s[1], s[2], s[3], t[1]), doubleArrayOf(s[2], s[3], s[4], t[2]))
        for (c in 0 until 3) {
            val p = (c until 3).maxByOrNull { abs(a[it][c]) }!!
            val tmp = a[c]; a[c] = a[p]; a[p] = tmp
            if (abs(a[c][c]) < 1e-12) return null
            for (r in 0 until 3) if (r != c) { val k = a[r][c] / a[c][c]; for (j in c..3) a[r][j] -= k * a[c][j] }
        }
        return doubleArrayOf(a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2], m)
    }

    private fun <T> parts(items: List<T>, k: Int, key: (T) -> Double): List<List<T>> {
        val s = items.sortedBy(key)
        return (0 until k).map { s.subList(it * s.size / k, (it + 1) * s.size / k) }
    }

    private fun mvs(rounds: List<Rd>, dpiOf: (Rd) -> Double = { it.rec?.dpi ?: 800.0 }): List<Mv> = rounds.flatMap { r ->
        r.moves.mapNotNull { m ->
            val peak = m.firstPeakSpeedCountsPerMs ?: return@mapNotNull null
            val e = m.firstErrorRatio ?: return@mapNotNull null
            Mv(AccelProcessor.speedInPerSecond(peak, dpiOf(r)), m.initialDistanceDegrees, e, r)
        }
    }

    private val bandLabels = listOf("<=0.80", "0.80-0.90", "0.90-0.98", "plain", "1.02-1.10", "1.10-1.25", ">1.25", "other")
    private fun band(s: Double?): String = when {
        s == null -> "other"
        s <= 0.80 -> "<=0.80"
        s < 0.90 -> "0.80-0.90"
        s < 0.98 -> "0.90-0.98"
        s <= 1.02 -> "plain"
        s < 1.10 -> "1.02-1.10"
        s <= 1.25 -> "1.10-1.25"
        else -> ">1.25"
    }

    private fun curveStr(c: AccelCurve): String {
        val spec = AccelSpec.encode(c).let { if (it.length > 90) it.take(90) + "..." else it }
        return "$spec | mult@3/6/12/25/50 in/s=" + listOf(3.0, 6.0, 12.0, 25.0, 50.0).joinToString("/") { f(c.multiplier(it), 3) }
    }

    // ---------- main ----------
    @Test
    fun report() {
        val dbPath = Path.of(System.getenv("AIMTUNE_REAL_DB"))
        val settings = SettingsStore(dbPath.toAbsolutePath().parent.resolve("settings.json")).load()
        weights = settings.toMetricWeights()
        val db = Database.open(dbPath)
        processor = RoundProcessor(db)
        repo = RoundRepository(db)
        sessions = repo.sessions()
        val sessionById = sessions.associateBy { it.id }
        rds = repo.rounds().map { row ->
            val rec = repo.loadRecording(row.id)
            val metrics = rec?.let { runCatching { computeRoundMetrics(it) }.getOrNull() }
            Rd(row, sessionById.getValue(row.sessionId), rec, metrics)
        }
        byId = rds.associateBy { it.row.id }
        val roundsOf = rds.groupBy { it.row.sessionId }
        searchRds = sessions.filter { it.kind != SessionKind.SENSITIVITY }.flatMap { s ->
            StoredSearches.searchRounds(s.kind, roundsOf[s.id].orEmpty().map { it.row }).map { byId.getValue(it.id) }
        }
        scores = buildScores()
        line("weights=${weights.values}  settings: dpi=${settings.dpi} game=${settings.gameId} sens=${settings.inGameSensitivity} style=${settings.aimStyle}")
        sectionA()
        sectionB()
        sectionC()
        val cal = sectionD()
        sectionE(cal)
        sectionF()
        sectionG(settings)
        val target = Path.of("build", "reports", "audit", "forensics.txt")
        Files.createDirectories(target.parent)
        Files.writeString(target, out.toString())
        println(out)
        processor.close()
    }

    private fun buildScores(): Map<Long, Double?> {
        val calc = ScoreCalculator(weights)
        val map = HashMap<Long, Double?>()
        val searchIds = searchRds.map { it.row.id }.toSet()
        calc.scoreRounds(searchRds.map { it.row.scoreInputs() }, searchRds.map { it.row.scenario }).forEachIndexed { i, s -> map[searchRds[i].row.id] = s }
        for ((_, list) in rds.filter { it.row.id !in searchIds }.groupBy { it.row.sessionId }) {
            calc.scoreRounds(list.map { it.row.scoreInputs() }, list.map { it.row.scenario }).forEachIndexed { i, s -> map[list[i].row.id] = s }
        }
        return map
    }

    // ---------- A ----------
    private fun sectionA() {
        line(); line("=== A. INVENTORY ===")
        line("sessions=${sessions.size} rounds=${rds.size} per kind: " + sessions.groupBy { it.kind.id }.map { (k, v) -> "$k=${v.size} sessions/${v.sumOf { s -> rds.count { it.row.sessionId == s.id } }} rounds" })
        for (s in sessions) {
            val rs = rds.filter { it.row.sessionId == s.id }
            val cms = rs.groupingBy { f(it.row.cm360, 1) }.eachCount().entries.take(4).joinToString(",") { "${it.key}x${it.value}" }
            val sc = rs.groupingBy { it.row.scenario }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }
            line("s${s.id} ${Instant.ofEpochMilli(s.startedAtMs).toString().take(16)}Z ${s.kind.id} dpi=${s.dpi.roundToInt()} n=${rs.size} [$sc] cm360=[$cms] accel=${rs.count { it.row.accel != null }} blind=${rs.count { it.row.blind }}")
        }
        val accelRounds = rds.filter { it.row.accel != null }
        line("rounds with accel != null: ${accelRounds.size}; by decoded spec (family: params -> n):")
        val groups = accelRounds.groupBy { r ->
            val c = r.curve
            when {
                c == null -> "UNDECODABLE"
                c is de.yuuto.aimtune.core.accel.NaturalCurve -> "natural decay=${f(c.decayRate, 3)} off=${f(c.inputOffset, 2)} limit=" + f(c.limit, 2)
                else -> AccelSpec.encode(c).take(70)
            }
        }
        groups.entries.sortedBy { it.key }.forEach { (k, v) -> line("  $k -> ${v.size} (sessions ${v.map { it.row.sessionId }.distinct().joinToString(",")})") }
    }

    // ---------- B ----------
    private fun searchSessions(cutoffMs: Long?): List<StoredSearches.SearchSession> =
        sessions.filter { it.kind != SessionKind.SENSITIVITY }.mapNotNull { s ->
            val rows = rds.filter { it.row.sessionId == s.id }.map { it.row }.filter { cutoffMs == null || it.startedAtMs < cutoffMs }
            val sr = StoredSearches.searchRounds(s.kind, rows)
            if (sr.isEmpty()) null else StoredSearches.SearchSession(s.id, s.dpi, sr)
        }

    private fun printSearch(label: String, ls: LastSearch?) {
        if (ls == null) { line("$label: none"); return }
        val r = ls.result
        line("$label: dpi=${ls.dpi.roundToInt()} cm360=${f(ls.cm360)} rounds=${ls.rounds} sessions=${ls.sessions}")
        if (r == null) { line("   result=null"); return }
        line("   best=${f(r.strength, 3)} ci=[${f(r.low, 3)},${f(r.high, 3)}] conf=${r.confidencePercent}% excludesPlain=${r.excludesPlain} clear=${ls.clear} atEdge=${r.atEdge} range=${f(r.rangeMin)}..${f(r.rangeMax)}")
        val offer = ls.offer
        line("   offer=" + (offer?.let { "${it.family} ${AccelSpec.encode(it.curve)}" } ?: "none"))
    }

    private fun sectionB() {
        line(); line("=== B. SEARCHES (scores pooled over all search rounds, per-scenario baselines) ===")
        for (s in sessions.filter { it.kind != SessionKind.SENSITIVITY }) {
            val all = rds.filter { it.row.sessionId == s.id }
            val sr = StoredSearches.searchRounds(s.kind, all.map { it.row }).map { byId.getValue(it.id) }
            if (sr.isEmpty()) { line("s${s.id} ${s.kind.id}: ${all.size} rounds, no search part"); continue }
            val byStrength = sr.groupBy { Math.round((it.strength ?: -1.0) * 100) / 100.0 }.toSortedMap()
            val flash = sr.count { it.row.scenario == "flash" }
            line("s${s.id} ${s.kind.id}: search rounds=${sr.size}/${all.size} distinctStrengths=${byStrength.size} flashShare=${f(flash.toDouble() / sr.size)} cm360=${f(sr.first().row.cm360)}")
            byStrength.entries.chunked(8).forEach { ch ->
                line("   " + ch.joinToString(" ") { (k, v) -> "${f(k)}:n${v.size}/${f(mean(v.mapNotNull { scores[it.row.id] }), 1)}" })
            }
        }
        val cutoff = ZonedDateTime.of(2026, 10, 18, 20, 15, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        line("cutoff 2026-10-18 20:15 Europe/Berlin = ${Instant.ofEpochMilli(cutoff)}")
        val weightsUsed = weights
        printSearch("(1) ALL rounds via RoundProcessor.lastSearch", processor.lastSearch(weightsUsed))
        printSearch("(2) rounds before cutoff via StoredSearches.pool", StoredSearches.pool(searchSessions(cutoff), weightsUsed))
        val after = searchSessions(null).sumOf { it.rounds.count { r -> r.startedAtMs >= cutoff } }
        line("search rounds started after cutoff: $after")
        val offer2 = StoredSearches.pool(searchSessions(cutoff), weightsUsed)?.offer?.curve
        val nat = offer2 as? de.yuuto.aimtune.core.accel.NaturalCurve
        line("exported file: natural decay 0.05 limit 0.70. Reconstructed (2) offer: ${offer2?.let { AccelSpec.encode(it) } ?: "none"} -> matches(limit within 0.005 and decay 0.05)=${nat != null && abs(nat.limit - 0.70) < 0.005 && abs(nat.decayRate - 0.05) < 1e-9}")
        val offer1 = processor.lastSearch(weightsUsed)?.offer?.curve as? de.yuuto.aimtune.core.accel.NaturalCurve
        line("current (1) offer limit=${f(offer1?.limit, 3)}; Velocity output / DPI 800 are not stored in the DB (not checkable)")
    }

    // ---------- C ----------
    private fun sectionC() {
        line(); line("=== C. A/B TESTS (tail of accel_ab sessions after the search part, plain vs curve pairs) ===")
        var total = 0
        for (s in sessions.filter { it.kind == SessionKind.ACCEL_AB }) {
            val all = rds.filter { it.row.sessionId == s.id }
            val searchN = StoredSearches.searchRounds(s.kind, all.map { it.row }).size
            val tail = all.drop(searchN)
            val outcomes = mutableListOf<AbOutcome>()
            val curves = mutableSetOf<String>()
            var i = 0; var pair = 0
            while (i < tail.size) {
                val a = tail.getOrNull(i); val b = tail.getOrNull(i + 1)
                if (a != null && b != null && a.plain != b.plain) {
                    val (p, c) = if (a.plain) a to b else b to a
                    outcomes += AbOutcome(pair, AbCondition.PLAIN, p.row.scoreInputs(), p.row.scenario)
                    outcomes += AbOutcome(pair, AbCondition.ACCEL, c.row.scoreInputs(), c.row.scenario)
                    curves += c.row.accel.orEmpty().take(60)
                    pair++; i += 2
                } else i++
            }
            total += pair
            val v = if (pair >= 2) evaluateAb(outcomes, weights) else null
            line("s${s.id}: searchPart=$searchN tail=${tail.size} pairs=$pair distinctCurves=${curves.size} ${curves.take(2)} verdict=" +
                (v?.let { "${it.status} mean=${f(it.meanDifference)} ci=[${f(it.lowerBound)},${f(it.upperBound)}]" } ?: "n/a (<2 pairs)"))
        }
        line("total pairs across accel_ab sessions=$total; accel_search sessions contain no A/B test by design")
    }

    // ---------- D ----------
    private fun sectionD(): List<Rd> {
        line(); line("=== D. ERROR ANALYSIS (lastCalibration path) ===")
        val cal = processor.lastCalibration()
        if (cal == null) { line("lastCalibration = null"); return emptyList() }
        val ids = repo.plainAccelRoundIds(cal.dpi, cal.cm360, AccelRun.CLICK_SCENARIOS.map { it.id }, PriorCalibration.CM360_TOLERANCE, PriorCalibration.MAX_ROUNDS)
        val rounds = ids.mapNotNull { byId[it] }
        line("setup dpi=${cal.dpi.roundToInt()} cm360=${f(cal.cm360)} rounds=${cal.data.rounds} samples=${cal.data.samples.size} sessions=${rounds.map { it.row.sessionId }.distinct()} scenarios=${rounds.groupingBy { it.row.scenario }.eachCount()}")
        val analysis = AccelAnalysis(cal.data.samples)
        val speeds = analysis.used.map { it.speed }
        line("used=${analysis.used.size} (of ${cal.data.samples.size}); speed in/s min=${f(speeds.minOrNull())} p5=${f(analysis.speedQuantile(0.05))} median=${f(median(speeds))} p95=${f(analysis.speedQuantile(0.95))} max=${f(speeds.maxOrNull())} binRange=${f(analysis.speedRange)}")
        line("bins (speed center in/s, n, wanted logGain, gain, se):")
        analysis.points.forEach { line("   ${f(it.speed)}  n=${it.count}  log=${f(it.logGain, 3)}  gain=${f(it.gain, 3)}  se=${f(it.standardError, 3)}") }
        val result = fitAccel(analysis)
        line("fitAccel: reason=${result.reasonCode} value=${result.reasonValue} \"${result.reason}\"")
        result.fits.forEach { line("   fit ${it.family}: bicGain=${f(it.bicGain, 1)} sensFactor=${f(it.sensitivityFactor, 3)} gainRange=${f(it.gainRange, 3)} chi2=${f(it.chiSquare, 2)} null=${f(it.nullChiSquare, 2)} | ${curveStr(it.curve)}") }
        val best = result.fits.maxByOrNull { it.bicGain }
        line("best family by bicGain: ${best?.family ?: "none"}; recommended=${result.recommended?.family ?: "none"}")
        line("LEGACY (unbounded) recommendation: " + (result.recommended?.let { "sens=${f(it.sensitivityFactor, 3)} gainRange=${f(it.gainRange, 3)} ${curveStr(it.curve)}" } ?: "none"))
        val bounded = boundedRecommendation(result, analysis)
        line("BOUNDED recommendation: " + (bounded?.let { "sens=${f(it.sensitivityFactor, 3)} ${curveStr(it.curve)}" } ?: "none"))
        return rounds
    }

    // ---------- E ----------
    private fun sectionE(calRounds: List<Rd>) {
        line(); line("=== E. CONFOUNDS ===")
        val searchPlain = searchRds.filter { it.plain }
        val sets = listOf("D plain click rounds" to calRounds, "search rounds (all, post-curve errors)" to searchRds, "search rounds, plain only" to searchPlain)
        for ((label, rounds) in sets) {
            val all = mvs(rounds)
            val used = all.filter { it.used }
            line("--- dataset: $label: rounds=${rounds.size} movements=${all.size} used=${used.size}")
            e1(used); e2(all); e3(used)
        }
        e4(); e5(); e6(); e7(calRounds)
    }

    private fun e1(used: List<Mv>) {
        val pos = used.filter { it.speed > 0 && it.dist > 0 }
        line("E1 corr(ln dist, ln speed)=${f(pearson(pos.map { ln(it.dist) }, pos.map { ln(it.speed) }), 3)}  corr(ln speed, err)=${f(pearson(pos.map { ln(it.speed) }, pos.map { it.err }), 3)}")
        val qs = parts(used, 5) { it.dist }
        val cs = qs.map { g -> pearson(g.map { it.speed }, g.map { it.err }) }
        line("   partial, corr(speed, err) within distance quintiles: " + qs.indices.joinToString(" ") { "[${f(qs[it].firstOrNull()?.dist, 0)}-${f(qs[it].lastOrNull()?.dist, 0)}deg n${qs[it].size} r=${f(cs[it], 2)}]" } + " mean r=${f(mean(cs.filter { !it.isNaN() }), 3)}")
    }

    private fun e2(all: List<Mv>) {
        val edges = listOf(0.0 to 3.0, 3.0 to 6.0 , 6.0 to 12.0, 12.0 to 25.0, 25.0 to 1e9)
        line("E2 slow reaches <3 in/s: ${all.count { it.speed < 3.0 }} of ${all.size}; mean signed err by speed: " +
            edges.joinToString(" ") { (lo, hi) -> val g = all.filter { it.speed >= lo && it.speed < hi }; "[${f(lo, 0)}-${if (hi > 1e8) "inf" else f(hi, 0)} n${g.size} mean=${f(mean(g.map { it.err }), 3)} med=${f(median(g.map { it.err }), 3)}]" })
    }

    private fun e3(used: List<Mv>) {
        val sq = parts(used, 5) { it.speed }
        line("E3 err per speed quintile (used): " + sq.joinToString(" ") { "[${f(it.firstOrNull()?.speed, 1)}-${f(it.lastOrNull()?.speed, 1)} in/s mean=${f(mean(it.map { m -> m.err }), 3)}]" })
        val dq = parts(used, 5) { it.dist }
        line("   err per distance quintile (used): " + dq.joinToString(" ") { "[${f(it.firstOrNull()?.dist, 1)}-${f(it.lastOrNull()?.dist, 1)} deg mean=${f(mean(it.map { m -> m.err }), 3)}]" })
    }

    private fun e4() {
        line("E4 scenario mix per strength band (search rounds): n | scenario counts | mean score per scenario")
        for (b in bandLabels) {
            val g = searchRds.filter { band(it.strength) == b }
            if (g.isEmpty()) continue
            val sc = g.groupBy { it.row.scenario }.toSortedMap()
            line("   $b n=${g.size} | " + sc.entries.joinToString(" ") { "${it.key}:${it.value.size}" } + " | " +
                sc.entries.joinToString(" ") { "${it.key}:${f(mean(it.value.mapNotNull { r -> scores[r.row.id] }), 1)}" })
        }
    }

    private fun e5() {
        line("E5 report timestamps of accel rounds (consecutive recorded events): share<=0ms, median ms, p99 ms, share>2ms")
        fun stats(rounds: List<Rd>): String {
            val dts = rounds.flatMap { r -> r.rec?.mouseEvents.orEmpty().zipWithNext { a, b -> (b.timeNanos - a.timeNanos) / 1e6 } }
            if (dts.isEmpty()) return "no events"
            return "events=${dts.size + rounds.size} zero/neg=${f(100.0 * dts.count { it <= 0.0 } / dts.size, 2)}% med=${f(median(dts), 2)} p99=${f(q(dts, 0.99), 2)} >2ms=${f(100.0 * dts.count { it > 2.0 } / dts.size, 1)}%"
        }
        val accel = rds.filter { it.row.accel != null }
        line("   all accel rounds (${accel.size}): ${stats(accel)}")
        line("   plain rounds (${rds.count { it.row.accel == null }}) for comparison: ${stats(rds.filter { it.row.accel == null })}")
        accel.groupBy { it.row.sessionId }.forEach { (sid, g) -> line("   s$sid (${g.size}): ${stats(g)}") }
        val worst = accel.maxByOrNull { r -> val d = r.rec?.mouseEvents.orEmpty().zipWithNext { a, b -> b.timeNanos - a.timeNanos }; d.count { it <= 0 }.toDouble() / maxOf(1, d.size) }
        line("   worst round by zero share: r${worst?.row?.id}: ${worst?.let { stats(listOf(it)) }}")
    }

    private fun e6() {
        line("E6 drift per session (scores per E-scoring: search rounds pooled, others per session): n, slope score/round, slope meanFirstErr/round, mean score first/last third")
        for (s in sessions) {
            val g = rds.filter { it.row.sessionId == s.id }.mapIndexed { i, r -> i to r }
            val sc = g.mapNotNull { (i, r) -> scores[r.row.id]?.let { i to it } }
            if (sc.size < 6) continue
            val (ss, _) = lin(sc.map { it.first.toDouble() }, sc.map { it.second })
            val er = g.mapNotNull { (i, r) -> r.moves.mapNotNull { it.firstErrorRatio }.takeIf { it.isNotEmpty() }?.let { i to it.average() } }
            val (es, _) = lin(er.map { it.first.toDouble() }, er.map { it.second })
            val third = sc.size / 3
            line("   s${s.id} ${s.kind.id} n=${sc.size} slopeScore=${f(ss, 3)} slopeErr=${f(es, 4)} first3rd=${f(mean(sc.take(third).map { it.second }), 1)} last3rd=${f(mean(sc.takeLast(third).map { it.second }), 1)}")
        }
    }

    private fun effectiveGain(r: Rd): Double? {
        val rec = r.rec ?: return null
        val curve = if (r.row.accel == null) NoAccel else r.curve ?: return null
        val proc = AccelProcessor(NoAccel, rec.dpi)
        var num = 0.0; var den = 0.0
        for (e in rec.mouseEvents) {
            val d = hypot(e.dx.toDouble(), e.dy.toDouble())
            num += d * curve.multiplier(proc.speedOf(e)); den += d
        }
        return if (den > 0) num / den else null
    }

    private val speedEdges = listOf(5.0, 10.0, 20.0, 40.0)
    private fun speedShare(rounds: List<Rd>): String {
        val w = DoubleArray(5)
        for (r in rounds) {
            val rec = r.rec ?: continue
            val proc = AccelProcessor(NoAccel, rec.dpi)
            for (e in rec.mouseEvents) {
                val d = hypot(e.dx.toDouble(), e.dy.toDouble())
                val v = proc.speedOf(e)
                w[speedEdges.indexOfFirst { v < it }.let { if (it < 0) 4 else it }] += d
            }
        }
        val t = w.sum()
        return "<5=${f(100 * w[0] / t, 1)}% 5-10=${f(100 * w[1] / t, 1)}% 10-20=${f(100 * w[2] / t, 1)}% 20-40=${f(100 * w[3] / t, 1)}% >40=${f(100 * w[4] / t, 1)}% (raw pre-curve speed, distance weighted)"
    }

    private fun e7(calRounds: List<Rd>) {
        line("E7 level test: score vs effective mean gain delivered (distance-weighted mean of curve multiplier over reports)")
        val pts = searchRds.mapNotNull { r -> val g = effectiveGain(r); val s = scores[r.row.id]; if (g != null && s != null) Triple(r, g, s) else null }
        val lg = pts.map { ln(it.second) }; val sc = pts.map { it.third }
        line("   n=${pts.size} effGain min=${f(pts.minOfOrNull { it.second }, 3)} mean=${f(mean(pts.map { it.second }), 3)} max=${f(pts.maxOfOrNull { it.second }, 3)}")
        val (sl, ic) = lin(lg, sc)
        line("   linear score = ${f(ic, 1)} + ${f(sl, 1)} * ln(g)  r=${f(pearson(lg, sc), 3)}")
        quad(lg, sc)?.let { a ->
            val xStar = a[3] - a[1] / (2 * a[2])
            line("   quadratic a2=${f(a[2], 1)} (concave=${a[2] < 0}); argmax effGain=${if (a[2] < 0) f(exp(xStar), 3) else "none (convex)"} inObservedRange=${a[2] < 0 && exp(xStar) in pts.minOf { it.second }..pts.maxOf { it.second }}")
        }
        val ls = pts.mapNotNull { p -> p.first.strength?.let { ln(it) to p.third } }
        quad(ls.map { it.first }, ls.map { it.second })?.let { a ->
            line("   for comparison, quadratic in ln(strength): argmax strength=${if (a[2] < 0) f(exp(a[3] - a[1] / (2 * a[2])), 3) else "none (convex)"}  corr(ln strength, ln effGain)=${f(pearson(ls.map { it.first }, pts.filter { it.first.strength != null }.map { ln(it.second) }), 3)}")
        }
        pts.groupBy { it.first.row.scenario }.forEach { (k, g) ->
            if (g.size >= 8) { val x = g.map { ln(it.second) }; line("   scenario $k n=${g.size}: slope=${f(lin(x, g.map { it.third }).first, 1)} r=${f(pearson(x, g.map { it.third }), 3)}") }
        }
        val effByBand = pts.groupBy { band(it.first.strength) }
        line("   effGain per band: " + bandLabels.filter { it in effByBand }.joinToString(" ") { "$it:${f(mean(effByBand.getValue(it).map { p -> p.second }), 3)}" })
        line("   speed distribution search rounds: " + speedShare(searchRds))
        line("   speed distribution D rounds:      " + speedShare(calRounds))
        searchRds.groupBy { it.row.scenario }.forEach { (k, g) -> line("   speed distribution search/$k: " + speedShare(g)) }
    }

    // ---------- F ----------
    private fun sectionF() {
        line(); line("=== F. GROSS FIRST-ERROR (plain rounds, accel == null; signed ratio, <0 undershoot, >0 overshoot) ===")
        val plain = rds.filter { it.row.accel == null }
        for ((label, g) in listOf("all plain" to plain, "sensitivity-kind" to plain.filter { it.session.kind == SessionKind.SENSITIVITY }, "accel-session plain" to plain.filter { it.session.kind != SessionKind.SENSITIVITY })) {
            val m = mvs(g)
            line("$label: rounds=${g.size} movements=${m.size} mean=${f(mean(m.map { it.err }), 3)} median=${f(median(m.map { it.err }), 3)} | used(${m.count { it.used }}) mean=${f(mean(m.filter { it.used }.map { it.err }), 3)} median=${f(median(m.filter { it.used }.map { it.err }), 3)}")
            m.groupBy { it.round.row.scenario }.toSortedMap().forEach { (k, v) ->
                line("   $k n=${v.size} mean=${f(mean(v.map { it.err }), 3)} median=${f(median(v.map { it.err }), 3)} usedMean=${f(mean(v.filter { it.used }.map { it.err }), 3)}")
            }
        }
    }

    // ---------- G ----------
    private fun sectionG(settings: de.yuuto.aimtune.storage.AppSettings) {
        line(); line("=== G. BLIND SENSITIVITY FINDER (kind sensitivity) ===")
        val ss = sessions.filter { it.kind == SessionKind.SENSITIVITY }
        val rs = rds.filter { it.session.kind == SessionKind.SENSITIVITY }
        val cms = rs.map { it.row.cm360 }
        line("sessions=${ss.size} rounds=${rs.size} blind=${rs.count { it.row.blind }} byScenario=${rs.groupingBy { it.row.scenario }.eachCount()} games=${ss.map { it.gameId }.distinct()} dpis=${ss.map { it.dpi.roundToInt() }.distinct()}")
        line("played cm360: min=${f(cms.minOrNull())} max=${f(cms.maxOrNull())} median=${f(median(cms))} distinct(0.1)=${cms.map { f(it, 1) }.distinct().size}")
        val dpi = settings.dpi ?: 800.0
        val yaw = 0.07
        val currentCm = settings.inGameSensitivity?.let { cm360(dpi, it, yaw) }
        line("current setting: sens=${settings.inGameSensitivity} -> cm360=${f(currentCm)}")
        if (currentCm == null || settings.padWidthCm == null) { line("settings incomplete"); return }
        val range = searchRange(settings.aimStyleOrNull() ?: AimStyle.MIXED, currentCm, settings.padWidthCm!!)
        val past = processor.pastBlindRounds(settings.gameId, dpi)
        line("blind rounds for game=${settings.gameId} dpi=${dpi.roundToInt()}: ${past.size}; search range ${f(range.minCm360)}..${f(range.maxCm360)}")
        val res = BlindTest.analyzeStored(range, RoundScorer(weights), past)
        if (res == null) line("no result") else {
            fun sens(cm: Double) = f(sensitivityFor(dpi, yaw, cm), 3)
            line("optimum cm360=${f(res.optimumCm360)} (Valorant sens ${sens(res.optimumCm360)}) ci=[${f(res.intervalLowCm360)},${f(res.intervalHighCm360)}] cm360 = sens [${sens(res.intervalHighCm360)},${sens(res.intervalLowCm360)}] conf=${res.confidencePercent}% atEdge=${res.atRangeEdge} drift=${res.driftDetected} regret=${f(res.expectedRegret, 2)} moreRounds=${res.recommendMoreRounds}")
        }
        val flick = mvs(rs.filter { it.row.scenario == "flick" })
        val sp = flick.map { it.speed }
        line("flick first-movement peak speed in/s (n=${sp.size}): p10=${f(q(sp, 0.1), 1)} p50=${f(q(sp, 0.5), 1)} p90=${f(q(sp, 0.9), 1)}")
        val spAll = mvs(rs).map { it.speed }
        line("all click scenarios (n=${spAll.size}): p10=${f(q(spAll, 0.1), 1)} p50=${f(q(spAll, 0.5), 1)} p90=${f(q(spAll, 0.9), 1)}")
    }
}
