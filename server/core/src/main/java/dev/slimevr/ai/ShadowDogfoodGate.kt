package dev.slimevr.ai

import kotlinx.serialization.Serializable

val REQUIRED_SHADOW_LAYOUTS: Set<Int> = linkedSetOf(5, 6, 8, 10)
val REQUIRED_SHADOW_ACTIVITIES: Set<String> = linkedSetOf(
	"STANDING", "SEATED", "LYING", "CROUCHING", "TRANSITION", "LOCOMOTION", "DANCE", "STATIONARY",
)

@Serializable
enum class ShadowDogfoodEvidenceKind { REAL_PLAYER, AUTOMATED_REPLAY }

@Serializable
data class ShadowDogfoodSession(
	val sessionId: String,
	val evidenceKind: ShadowDogfoodEvidenceKind,
	val durationSeconds: Long,
	val trackerCount: Int,
	val activities: Set<String>,
	val resetCount: Int,
	val lateEpochRejections: Int,
	val failOpenChecks: Int,
	val failOpenFailures: Int,
	val aiAppliedCorrections: Long,
	val nonIdentityTrackingCorrections: Long,
	val baselineTickP95Micros: Long,
	val shadowTickP95Micros: Long,
	val queueDrained: Boolean,
	val queueFinalDepth: Int,
	val visibleRegressionReported: Boolean,
)

@Serializable
data class ShadowDogfoodReport(
	val format: String = "nekovr-shadow-dogfood-v1",
	val schemaVersion: Int = 1,
	val generatedUtc: String,
	val buildCommit: String,
	val modelSha256: String,
	val passed: Boolean,
	val sessions: List<ShadowDogfoodSession>,
)

@Serializable
data class ShadowDogfoodFinding(val code: String, val detail: String)

@Serializable
data class ShadowDogfoodGateReport(
	val format: String = "nekovr-shadow-dogfood-gate-v1",
	val passed: Boolean,
	val modelSha256: String,
	val coveredLayouts: Set<Int>,
	val coveredActivities: Set<String>,
	val findings: List<ShadowDogfoodFinding>,
)

object ShadowDogfoodGate {
	fun evaluate(report: ShadowDogfoodReport, maximumTickP95RegressionMicros: Long = 500): ShadowDogfoodGateReport {
		val findings = mutableListOf<ShadowDogfoodFinding>()
		if (report.schemaVersion != 1 || report.format != "nekovr-shadow-dogfood-v1") finding(findings, "SCHEMA", "Unsupported shadow dogfood report")
		if (!Regex("^[0-9a-f]{64}$").matches(report.modelSha256)) finding(findings, "MODEL_HASH", "Model SHA-256 is invalid")
		val real = report.sessions.filter { it.evidenceKind == ShadowDogfoodEvidenceKind.REAL_PLAYER }
		if (real.size != report.sessions.size) finding(findings, "NON_DOGFOOD_EVIDENCE", "Automated replay cannot substitute for real player dogfood")
		val layouts = real.mapTo(linkedSetOf()) { it.trackerCount }
		val activities = real.flatMapTo(linkedSetOf()) { it.activities.map(String::uppercase) }
		val missingLayouts = REQUIRED_SHADOW_LAYOUTS - layouts
		if (missingLayouts.isNotEmpty()) finding(findings, "MISSING_LAYOUTS", missingLayouts.sorted().joinToString())
		val missingActivities = REQUIRED_SHADOW_ACTIVITIES - activities
		if (missingActivities.isNotEmpty()) finding(findings, "MISSING_ACTIVITIES", missingActivities.sorted().joinToString())
		if (real.any { it.durationSeconds <= 0 }) finding(findings, "EMPTY_SESSION", "Every dogfood session must have positive duration")
		if (real.any { it.resetCount <= 0 || it.lateEpochRejections <= 0 }) finding(findings, "RESET_UNVERIFIED", "Every layout needs reset and late-epoch evidence")
		if (real.any { it.failOpenChecks <= 0 || it.failOpenFailures != 0 }) finding(findings, "FAIL_OPEN", "Forced fail-open checks are missing or failed")
		if (real.any { it.aiAppliedCorrections != 0L || it.nonIdentityTrackingCorrections != 0L }) finding(findings, "SHADOW_APPLIED", "Shadow mode changed tracking output")
		if (real.any { !it.queueDrained || it.queueFinalDepth != 0 }) finding(findings, "QUEUE", "Inference queue did not drain")
		if (real.any { it.shadowTickP95Micros - it.baselineTickP95Micros > maximumTickP95RegressionMicros }) {
			finding(findings, "PERFORMANCE", "Shadow tick p95 regression exceeds $maximumTickP95RegressionMicros us")
		}
		if (real.any { it.visibleRegressionReported }) finding(findings, "VISIBLE_REGRESSION", "A player reported visible regression")
		if (!report.passed) finding(findings, "REPORT_NOT_SIGNED_OFF", "Dogfood report was not signed off as passed")
		return ShadowDogfoodGateReport(
			passed = findings.isEmpty(), modelSha256 = report.modelSha256,
			coveredLayouts = layouts, coveredActivities = activities, findings = findings,
		)
	}

	private fun finding(target: MutableList<ShadowDogfoodFinding>, code: String, detail: String) {
		target += ShadowDogfoodFinding(code, detail)
	}
}
