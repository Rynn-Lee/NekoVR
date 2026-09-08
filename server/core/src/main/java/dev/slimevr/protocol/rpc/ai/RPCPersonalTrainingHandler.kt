package dev.slimevr.protocol.rpc.ai

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.ai.personal.*
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.protocol.ProtocolAPI
import dev.slimevr.protocol.rpc.RPCHandler
import solarxr_protocol.rpc.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class PersonalEligibility(
	val ready: Boolean,
	val coverage: PersonalCoverageSummary,
	val findings: List<Pair<String, String>> = emptyList(),
)

fun interface PersonalEligibilityProvider {
	fun analyze(profileId: String, baseModelSha256: String, sessionHashes: Set<String>): PersonalEligibility
}

fun interface PersonalTrainingLifecycle {
	fun execute(operation: Int, job: PersonalJobRuntime?, outputPath: String?)
}

/** One typed, transaction-correlated RPC surface for the complete personal trainer lifecycle. */
class RPCPersonalTrainingHandler(
	private val rpcHandler: RPCHandler,
	private val api: ProtocolAPI,
	private val store: PersonalTrainingStore = api.server.personalTrainingStore,
	private val coordinator: PersonalTrainingCoordinator = api.server.personalTrainingCoordinator,
	private val eligibility: PersonalEligibilityProvider = PersonalEligibilityProvider { _, _, sessions ->
		PersonalEligibility(
			sessions.isNotEmpty(),
			PersonalCoverageSummary(0.0, 0, 0, 0.0),
			if (sessions.isEmpty()) listOf("NO_SESSIONS" to "Select at least one validated dataset session") else emptyList(),
		)
	},
	private val lifecycle: PersonalTrainingLifecycle = PersonalTrainingLifecycle { operation, _, _ ->
		throw IllegalStateException("Personal trainer lifecycle operation ${PersonalTrainingOperation.name(operation)} is unavailable until a signed worker is installed")
	},
) {
	init { rpcHandler.registerPacketListener(RpcMessage.PersonalTrainingRequest, ::onRequest) }

	fun onRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(PersonalTrainingRequest()) as? PersonalTrainingRequest ?: return
		val requestId = request.requestId().orEmpty()
		runCatching {
			when (request.operation()) {
				PersonalTrainingOperation.PROFILE_LIST -> sendProfiles(conn, header, requestId)
				PersonalTrainingOperation.PROFILE_CREATE -> createProfile(conn, header, request, requestId)
				PersonalTrainingOperation.ELIGIBILITY -> sendEligibility(conn, header, request, requestId)
				PersonalTrainingOperation.JOB_CREATE -> createJob(conn, header, request, requestId)
				PersonalTrainingOperation.JOB_START, PersonalTrainingOperation.JOB_RESUME -> sendAction(conn, header, requestId, request.operation(), coordinator.start(required(request.jobId(), "job_id"), request.expectedJobVersion()))
				PersonalTrainingOperation.JOB_PAUSE -> sendAction(conn, header, requestId, request.operation(), coordinator.pause(required(request.jobId(), "job_id"), request.expectedJobVersion()))
				PersonalTrainingOperation.JOB_CANCEL -> sendAction(conn, header, requestId, request.operation(), coordinator.cancel(required(request.jobId(), "job_id"), request.expectedJobVersion()))
				PersonalTrainingOperation.STATUS -> sendStatus(conn, header, requestId, coordinator.status(required(request.jobId(), "job_id")) ?: error("Job not found"))
				PersonalTrainingOperation.INSTALL, PersonalTrainingOperation.PROBE,
				PersonalTrainingOperation.EVALUATE, PersonalTrainingOperation.EXPORT,
				PersonalTrainingOperation.ACTIVATE -> sendLifecycleAction(conn, header, request, requestId)
				else -> error("Unsupported personal training operation")
			}
		}.onFailure { sendFailure(conn, header, requestId, request.operation(), it) }
	}

	private fun createProfile(conn: GenericConnection, header: RpcMessageHeader, request: PersonalTrainingRequest, requestId: String) {
		val now = Instant.now().toString()
		store.saveProfile(PersonalProfileMetadata(
			profileId = request.profileId()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
			localPseudonym = required(request.pseudonym(), "pseudonym"), createdUtc = now, updatedUtc = now,
			bodyProportionsMeters = emptyMap(), compatibility = PersonalCompatibility(emptySet(), emptyList(), emptySet()),
			provenance = PersonalProvenance(System.getProperty("nekovr.commit", "unknown")),
		))
		sendProfiles(conn, header, requestId)
	}

	private fun createJob(conn: GenericConnection, header: RpcMessageHeader, request: PersonalTrainingRequest, requestId: String) {
		val profileId = required(request.profileId(), "profile_id")
		store.loadProfile(profileId)
		val base = sha256(request.baseModelSha256(), "base_model_sha256")
		val sessions = (0 until request.sessionSha256Length()).map { sha256(request.sessionSha256(it), "session_sha256") }.toSet()
		require(sessions.isNotEmpty()) { "At least one session is required" }
		val now = Instant.now().toString()
		val settings = "${request.preset()}:${request.provider()}:${request.resources()?.let { "${it.cpuThreads()}:${it.gpuMemoryMib()}:${it.gpuUtilizationPercent()}:${it.ramMib()}:${it.diskMib()}:${it.ioMibPerSecond()}:${it.activeVrPolicy()}" }}"
		val resourceBudget = request.resources()?.let { PersonalResourceBudget(it.cpuThreads(), it.gpuMemoryMib().toInt(), it.gpuUtilizationPercent(), it.ramMib().toInt(), it.diskMib().toInt(), it.ioMibPerSecond(), ActiveVrPolicy.entries[it.activeVrPolicy()]) }
		val job = PersonalJobMetadata(
			jobId = UUID.randomUUID().toString(), profileId = profileId, baseModelSha256 = base,
			featureSchemaSha256 = "0".repeat(64), selectedSessionHashes = sessions,
			settingsSha256 = digest(settings), preset = PersonalTrainingPreset.entries[request.preset()], provider = PersonalTrainingProvider.name(request.provider()), resourceBudget = resourceBudget,
			coverage = eligibility.analyze(profileId, base, sessions).coverage, state = PersonalJobState.CREATED, createdUtc = now, updatedUtc = now,
			provenance = PersonalProvenance(System.getProperty("nekovr.commit", "unknown")),
		)
		sendAction(conn, header, requestId, request.operation(), coordinator.register(job))
	}

	private fun sendLifecycleAction(conn: GenericConnection, header: RpcMessageHeader, request: PersonalTrainingRequest, requestId: String) {
		// Lifecycle commands are accepted only for known jobs; the external signed worker owns execution.
		val runtime = request.jobId()?.takeIf(String::isNotBlank)?.let { coordinator.status(it) }
		if (request.operation() >= PersonalTrainingOperation.EVALUATE) require(runtime?.job?.state == PersonalJobState.COMPLETED) { "A completed job is required" }
		lifecycle.execute(request.operation(), runtime, request.outputPath())
		sendAction(conn, header, requestId, request.operation(), runtime)
	}

	private fun sendProfiles(conn: GenericConnection, header: RpcMessageHeader, requestId: String) {
		val response = PersonalTrainingProfilesResponseT().apply {
			this.requestId = requestId
			profiles = store.listProfiles().map { profile -> PersonalTrainingProfileT().apply {
				profileId = profile.profileId; pseudonym = profile.localPseudonym
				bodyRoleIds = profile.compatibility.bodyRoleIds.toIntArray(); sensorFamilies = profile.compatibility.sensorFamilies.toTypedArray()
			} }.toTypedArray()
		}
		val fbb = FlatBufferBuilder(512)
		val offset = PersonalTrainingProfilesResponse.pack(fbb, response)
		finishSend(conn, header, RpcMessage.PersonalTrainingProfilesResponse, fbb, offset)
	}

	private fun sendEligibility(conn: GenericConnection, header: RpcMessageHeader, request: PersonalTrainingRequest, requestId: String) {
		val sessions = (0 until request.sessionSha256Length()).map { sha256(request.sessionSha256(it), "session_sha256") }.toSet()
		val result = eligibility.analyze(required(request.profileId(), "profile_id"), sha256(request.baseModelSha256(), "base_model_sha256"), sessions)
		val response = PersonalTrainingEligibilityResponseT().apply {
			this.requestId = requestId; ready = result.ready
			coverage = PersonalTrainingCoverageT().apply { usableSeconds = result.coverage.usableSeconds; usableWindows = result.coverage.usableWindows; resetLabels = result.coverage.resetLabels; cleanSeconds = result.coverage.cleanSeconds; layouts = emptyArray(); sensorFamilies = emptyArray(); activities = emptyArray() }
			findings = result.findings.map { (code, message) -> PersonalTrainingFindingT().apply { this.code = code; blocking = true; this.message = message; sessionSha256 = "" } }.toTypedArray()
			recommendedSessionSha256 = sessions.toTypedArray()
		}
		val fbb = FlatBufferBuilder(512); val offset = PersonalTrainingEligibilityResponse.pack(fbb, response); finishSend(conn, header, RpcMessage.PersonalTrainingEligibilityResponse, fbb, offset)
	}

	private fun sendStatus(conn: GenericConnection, header: RpcMessageHeader, requestId: String, runtime: PersonalJobRuntime) {
		val response = PersonalTrainingStatusResponseT().apply {
			this.requestId = requestId; jobId = runtime.job.jobId; jobVersion = runtime.version
			state = runtime.job.state.ordinal + 1; stage = runtime.stage.ordinal; progress = runtime.progress; etaSeconds = runtime.etaSeconds
			currentMetric = runtime.currentMetric.takeIf(Double::isFinite) ?: 0.0; bestMetric = runtime.bestMetric.takeIf(Double::isFinite) ?: 0.0
			preset = runtime.job.preset.ordinal; provider = runCatching { PersonalTrainingProvider.names.indexOf(runtime.job.provider) }.getOrDefault(PersonalTrainingProvider.AUTO)
			resources = runtime.job.resourceBudget?.let { value -> PersonalTrainingResourcePolicyT().apply { cpuThreads = value.cpuThreads; gpuMemoryMib = value.gpuMemoryMiB.toLong(); gpuUtilizationPercent = value.gpuUtilizationPercent; ramMib = value.ramMiB.toLong(); diskMib = value.diskMiB.toLong(); ioMibPerSecond = value.ioMiBPerSecond; activeVrPolicy = value.activeVrPolicy.ordinal } }
			usage = runtime.resourceUsage?.let { value -> PersonalTrainingResourceUsageT().apply { cpuThreads = value.cpuThreads; gpuMemoryMib = value.gpuMemoryMiB.toLong(); gpuUtilizationPercent = value.gpuUtilizationPercent; ramMib = value.ramMiB.toLong(); diskMib = value.diskMiB.toLong(); ioMibPerSecond = value.ioMiBPerSecond; activeVr = value.activeVr } }
			coverage = runtime.job.coverage?.let { value -> PersonalTrainingCoverageT().apply { usableSeconds = value.usableSeconds; usableWindows = value.usableWindows; resetLabels = value.resetLabels; cleanSeconds = value.cleanSeconds; layouts = emptyArray(); sensorFamilies = emptyArray(); activities = emptyArray() } }
			checkpointId = runtime.job.activeCheckpointId ?: ""; recoverable = runtime.recoverable; error = runtime.error ?: ""
		}
		val fbb = FlatBufferBuilder(512); val offset = PersonalTrainingStatusResponse.pack(fbb, response); finishSend(conn, header, RpcMessage.PersonalTrainingStatusResponse, fbb, offset)
	}

	private fun sendAction(conn: GenericConnection, header: RpcMessageHeader, requestId: String, operation: Int, runtime: PersonalJobRuntime?) {
		val response = PersonalTrainingActionResponseT().apply { this.requestId = requestId; this.operation = operation; success = true; jobId = runtime?.job?.jobId ?: ""; jobVersion = runtime?.version ?: 0 }
		val fbb = FlatBufferBuilder(256); val offset = PersonalTrainingActionResponse.pack(fbb, response); finishSend(conn, header, RpcMessage.PersonalTrainingActionResponse, fbb, offset)
	}

	private fun sendFailure(conn: GenericConnection, header: RpcMessageHeader, requestId: String, operation: Int, error: Throwable) {
		val message = error.message ?: error.javaClass.simpleName
		val code = when {
			message.contains("version conflict", ignoreCase = true) -> PersonalTrainingErrorCode.VERSION_CONFLICT
			message.contains("not found", ignoreCase = true) -> PersonalTrainingErrorCode.NOT_FOUND
			message.contains("not installed", ignoreCase = true) || message.contains("unavailable", ignoreCase = true) -> PersonalTrainingErrorCode.WORKER_UNAVAILABLE
			else -> PersonalTrainingErrorCode.INVALID_ARGUMENT
		}
		val response = PersonalTrainingActionResponseT().apply { this.requestId = requestId; this.operation = operation; success = false; errorCode = code; this.error = message; jobId = "" }
		val fbb = FlatBufferBuilder(256); val offset = PersonalTrainingActionResponse.pack(fbb, response); finishSend(conn, header, RpcMessage.PersonalTrainingActionResponse, fbb, offset)
	}

	private fun finishSend(conn: GenericConnection, header: RpcMessageHeader, type: Byte, fbb: FlatBufferBuilder, offset: Int) { fbb.finish(rpcHandler.createRPCMessage(fbb, type, offset, header)); conn.send(fbb.dataBuffer()) }
	private fun required(value: String?, field: String) = value?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$field is required")
	private fun sha256(value: String?, field: String) = required(value, field).lowercase().also { require(it.matches(Regex("[0-9a-f]{64}"))) { "$field must be SHA-256" } }
	private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
