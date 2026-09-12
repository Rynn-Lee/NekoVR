package dev.slimevr.protocol.rpc.ai

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.ai.*
import dev.slimevr.config.AIDriftConfig
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.protocol.ProtocolAPI
import dev.slimevr.protocol.rpc.RPCHandler
import solarxr_protocol.MessageBundle
import solarxr_protocol.datatypes.TransactionId
import solarxr_protocol.rpc.*
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Typed server-authoritative control plane for managed AI drift models. */
class RPCModelHandler(
	private val rpcHandler: RPCHandler? = null,
	private val api: ProtocolAPI? = null,
	val engine: AIDriftEngine = api?.server?.aiDriftEngine ?: AIDriftEngine(),
	val workerExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "ai-model-rpc-worker").apply { isDaemon = true }
	},
) {
	private val manager = engine.modelManager

	init {
		rpcHandler?.registerPacketListener(RpcMessage.ModelImportRequest, ::onImportRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelCatalogRefreshRequest, ::onCatalogRefreshRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelCatalogRequest, ::onCatalogRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelDownloadRequest, ::onDownloadRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelLoadRequest, ::onLoadRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelUnloadRequest, ::onUnloadRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelConfigureRequest, ::onConfigureRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelRuntimeStatusRequest, ::onRuntimeStatusRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelHistoryRequest, ::onHistoryRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelPinRequest, ::onPinRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelSwitchRequest, ::onSwitchRequest)
		rpcHandler?.registerPacketListener(RpcMessage.ModelRollbackRequest, ::onRollbackRequest)
	}

	fun onImportRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelImportRequest()) as? ModelImportRequest ?: return
		val requestId = request.requestId().orEmpty()
		workerExecutor.execute {
			runCatching {
				val modelPath = requiredPath(request.modelPath(), "model_path")
				val metadataPath = requiredPath(request.metadataPath(), "metadata_path")
				manager.importLocal(modelPath, metadataPath, request.expectedModelSha256()?.takeIf(String::isNotBlank))
			}.onSuccess { artifact ->
				sendAction(conn, header, requestId, AIModelOperation.IMPORT, true, artifact.metadata.modelSha256)
			}.onFailure { error -> sendFailure(conn, header, requestId, AIModelOperation.IMPORT, error) }
		}
	}

	fun onCatalogRefreshRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelCatalogRefreshRequest()) as? ModelCatalogRefreshRequest ?: return
		val requestId = request.requestId().orEmpty()
		workerExecutor.execute {
			val result = manager.refreshRemoteCatalog(request.catalogUrl()?.takeIf(String::isNotBlank) ?: RemoteModelManager.DEFAULT_CATALOG_URL)
			sendCatalog(conn, header, requestId, result.error)
		}
	}

	fun onCatalogRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelCatalogRequest()) as? ModelCatalogRequest ?: return
		sendCatalog(conn, header, request.requestId().orEmpty())
	}

	fun onDownloadRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelDownloadRequest()) as? ModelDownloadRequest ?: return
		val requestId = request.requestId().orEmpty()
		val hash = request.modelSha256().orEmpty()
		workerExecutor.execute {
			runCatching {
				manager.download(hash) { progress -> sendProgress(conn, header, requestId, progress) }
			}.onSuccess { artifact ->
				sendAction(conn, header, requestId, AIModelOperation.DOWNLOAD, true, artifact.metadata.modelSha256)
			}.onFailure { error ->
				sendProgress(conn, header, requestId, ModelDownloadProgress(ModelDownloadStage.COMPLETED, 0, 0, hash), error)
				sendFailure(conn, header, requestId, AIModelOperation.DOWNLOAD, error)
			}
		}
	}

	fun onLoadRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelLoadRequest()) as? ModelLoadRequest ?: return
		val requestId = request.requestId().orEmpty()
		workerExecutor.execute {
			runCatching {
				activate(request.modelSha256().orEmpty(), executionProvider(request.provider()))
			}.onSuccess { result ->
				sendAction(conn, header, requestId, AIModelOperation.LOAD, true, result.modelSha256.orEmpty(), result.provider)
			}.onFailure { error -> sendFailure(conn, header, requestId, AIModelOperation.LOAD, error) }
		}
	}

	fun onHistoryRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelHistoryRequest()) as? ModelHistoryRequest ?: return
		sendHistory(conn, header, request.requestId().orEmpty())
	}

	fun onPinRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelPinRequest()) as? ModelPinRequest ?: return
		val requestId = request.requestId().orEmpty()
		workerExecutor.execute {
			runCatching {
				val artifact = manager.resolve(request.modelSha256().orEmpty())
					?: throw ModelCatalogException(ModelCatalogErrorCode.MODEL_NOT_FOUND, "Managed model was not found")
				manager.history.pin(artifact, request.pinned())
			}.onSuccess { sendAction(conn, header, requestId, AIModelOperation.PIN, true, request.modelSha256().orEmpty()) }
				.onFailure { sendFailure(conn, header, requestId, AIModelOperation.PIN, it) }
		}
	}

	fun onSwitchRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelSwitchRequest()) as? ModelSwitchRequest ?: return
		val requestId = request.requestId().orEmpty()
		workerExecutor.execute {
			runCatching { activate(request.modelSha256().orEmpty(), executionProvider(request.provider())) }
				.onSuccess { result -> sendAction(conn, header, requestId, AIModelOperation.SWITCH, true, result.modelSha256.orEmpty(), result.provider) }
				.onFailure { sendFailure(conn, header, requestId, AIModelOperation.SWITCH, it) }
		}
	}

	fun onRollbackRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelRollbackRequest()) as? ModelRollbackRequest ?: return
		val requestId = request.requestId().orEmpty()
		workerExecutor.execute {
			runCatching {
				val artifact = manager.history.rollbackArtifact()
					?: throw ModelCatalogException(ModelCatalogErrorCode.MODEL_NOT_FOUND, "No compatible rollback model is available")
				activate(artifact.metadata.modelSha256, executionProvider(request.provider()))
			}.onSuccess { result -> sendAction(conn, header, requestId, AIModelOperation.ROLLBACK, true, result.modelSha256.orEmpty(), result.provider) }
				.onFailure { sendFailure(conn, header, requestId, AIModelOperation.ROLLBACK, it) }
		}
	}

	private fun activate(modelSha256: String, provider: ExecutionProviderType): ModelActivationResult {
		val artifact = manager.resolve(modelSha256)
			?: throw ModelCatalogException(ModelCatalogErrorCode.MODEL_NOT_FOUND, "Managed model was not found")
		manager.history.compatibilityError(artifact, engine.currentMappings(), engine.config.contextFrames)?.let {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, it)
		}
		val previousHash = engine.activeStatus?.modelSha256
		val previousProvider = engine.currentProvider
		val previousArtifact = previousHash?.let(manager::resolve)
		val result = engine.loadModel(artifact.modelPath, artifact.sidecarPath, provider)
		if (!result.activated) throw result.failure ?: IllegalStateException("Model activation failed")
		try {
			manager.history.recordActivation(artifact, previousHash)
		} catch (error: Throwable) {
			if (previousArtifact != null && previousProvider != null) {
				engine.loadModel(previousArtifact.modelPath, previousArtifact.sidecarPath, previousProvider)
			} else {
				engine.unloadModel()
			}
			throw error
		}
		return result
	}

	fun onUnloadRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelUnloadRequest()) as? ModelUnloadRequest ?: return
		engine.unloadModel()
		sendAction(conn, header, request.requestId().orEmpty(), AIModelOperation.UNLOAD, true)
	}

	fun onConfigureRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelConfigureRequest()) as? ModelConfigureRequest ?: return
		val requestId = request.requestId().orEmpty()
		runCatching {
			applyConfiguration(request)
			persistConfiguration()
		}.onSuccess { sendAction(conn, header, requestId, AIModelOperation.CONFIGURE, true, provider = engine.currentProvider) }
			.onFailure { sendFailure(conn, header, requestId, AIModelOperation.CONFIGURE, it) }
	}

	fun onRuntimeStatusRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val request = header.message(ModelRuntimeStatusRequest()) as? ModelRuntimeStatusRequest ?: return
		val response = engine.runtimeStatus().toRpc(request.requestId().orEmpty())
		val fbb = FlatBufferBuilder(512)
		val offset = ModelRuntimeStatusResponse.pack(fbb, response)
		fbb.finish(createRPCMessage(fbb, RpcMessage.ModelRuntimeStatusResponse, offset, header))
		conn.send(fbb.dataBuffer())
	}

	private fun applyConfiguration(request: ModelConfigureRequest) {
		val mask = request.fieldMask()
		require(mask and CONFIG_ALL.inv() == 0L) { "Unknown AI configuration field mask" }
		val provider = executionProvider(request.provider())
		val context = request.contextFrames()
		val confidence = request.confidenceThreshold()
		val correction = request.maximumCorrectionRadians()
		val rate = request.maximumRateRadiansPerSecond()
		val acceleration = request.maximumAccelerationRadiansPerSecondSquared()
		val smoothing = request.smoothing()
		val decay = request.staleDecaySeconds()
		if (mask has CONTEXT) require(context in 1..AIDriftConfig.MAXIMUM_CONTEXT_FRAMES) { "context_frames must be between 1 and ${AIDriftConfig.MAXIMUM_CONTEXT_FRAMES}" }
		if (mask has CONFIDENCE) require(confidence.isFinite() && confidence in 0f..1f) { "confidence_threshold must be between 0 and 1" }
		if (mask has LIMITS) require(
			correction.isFinite() && correction in Float.MIN_VALUE..AIDriftConfig.MAXIMUM_CORRECTION_RADIANS &&
				rate.isFinite() && rate in Float.MIN_VALUE..AIDriftConfig.MAXIMUM_RATE_RADIANS_PER_SECOND &&
				acceleration.isFinite() && acceleration in Float.MIN_VALUE..AIDriftConfig.MAXIMUM_ACCELERATION_RADIANS_PER_SECOND_SQUARED,
		) { "Safety limits are outside supported bounds" }
		if (mask has SMOOTHING) require(smoothing.isFinite() && smoothing in 0f..1f) { "smoothing must be between 0 and 1" }
		if (mask has STALE_DECAY) require(decay.isFinite() && decay in Float.MIN_VALUE..AIDriftConfig.MAXIMUM_STALE_DECAY_SECONDS) { "stale_decay_seconds is outside supported bounds" }
		val legacy = if (mask has LEGACY_MODE) when (request.legacyMode()) {
			AILegacyDriftMode.REPLACE -> LegacyDriftCompensationMode.REPLACE
			AILegacyDriftMode.COMPOSE -> LegacyDriftCompensationMode.COMPOSE
			else -> throw IllegalArgumentException("Unknown legacy drift mode")
		} else null
		val mappings = if (mask has MAPPINGS) (0 until request.mappingsLength()).map { index ->
			val mapping = checkNotNull(request.mappings(index))
			require(mapping.trackerId() <= Int.MAX_VALUE.toLong()) { "tracker_id is outside supported bounds" }
			TrackerSlotMapping(mapping.trackerId().toInt(), mapping.bodyRoleId(), mapping.slot())
		} else null
		if (mappings != null) {
			require(mappings.map { it.trackerId }.toSet().size == mappings.size) { "Tracker IDs must be unique" }
			require(mappings.map { it.slot }.toSet().size == mappings.size) { "Slots must be unique" }
		}
		if (mappings != null) engine.configureMappings(mappings)
		if (mask has CONTEXT) engine.configureContextFrames(context)
		with(engine.config) {
			if (mask has ENABLED) enabled = request.enabled()
			if (mask has PROVIDER) requestedProvider = provider
			if (mask has CONFIDENCE) confidenceThreshold = confidence
			if (mask has LIMITS) {
				maximumCorrectionRadians = correction
				maximumAngularRateRadiansPerSecond = rate
				maximumAngularAccelerationRadiansPerSecondSquared = acceleration
			}
			if (mask has SMOOTHING) this.smoothing = smoothing
			if (mask has STALE_DECAY) staleDecaySeconds = decay
			if (legacy != null) legacyDriftCompensationMode = legacy
		}
	}

	private fun persistConfiguration() {
		val server = api?.server ?: return
		server.configManager.vrConfig.aiDrift.capture(engine.config, engine.currentMappings())
		server.configManager.saveConfig()
	}

	private fun sendCatalog(conn: GenericConnection, header: RpcMessageHeader, requestId: String, error: ModelCatalogException? = null) {
		val catalogModels = manager.catalogStatus().map { status ->
			AIModelDescriptorT().apply {
				modelId = status.entry.modelId; modelVersion = status.entry.modelVersion; name = status.entry.name
				description = status.entry.description; sizeTier = status.entry.sizeTier; modelSha256 = status.entry.modelSha256
				modelSizeBytes = status.entry.modelSizeBytes; metadataSha256 = status.entry.sidecarSha256; metadataSizeBytes = status.entry.sidecarSizeBytes
				downloaded = status.downloaded; compatible = status.compatible; compatibilityError = status.compatibilityError.orEmpty()
			}
		}.toMutableList()
		val catalogHashes = catalogModels.map { it.modelSha256 }.toSet()
		manager.store.list().filter { it.metadata.modelSha256 !in catalogHashes }.forEach { artifact ->
			catalogModels += AIModelDescriptorT().apply {
				modelId = artifact.metadata.modelId; modelVersion = artifact.metadata.modelVersion; name = artifact.metadata.modelId
				description = "Locally imported model"; sizeTier = "local"; modelSha256 = artifact.metadata.modelSha256
				modelSizeBytes = artifact.metadata.modelSizeBytes; metadataSha256 = ""; metadataSizeBytes = runCatching { java.nio.file.Files.size(artifact.sidecarPath) }.getOrDefault(0)
				downloaded = true; compatible = true; compatibilityError = ""
			}
		}
		val response = ModelCatalogResponseT().apply {
			this.requestId = requestId; catalogVersion = manager.catalogVersion().toLong(); models = catalogModels.toTypedArray()
			errorCode = error?.toRpcError() ?: AIModelErrorCode.OK; this.error = error?.message.orEmpty()
		}
		val fbb = FlatBufferBuilder(512)
		val offset = ModelCatalogResponse.pack(fbb, response)
		fbb.finish(createRPCMessage(fbb, RpcMessage.ModelCatalogResponse, offset, header))
		conn.send(fbb.dataBuffer())
	}

	private fun sendHistory(conn: GenericConnection, header: RpcMessageHeader, requestId: String) {
		val statuses = manager.history.statuses(engine.activeStatus?.modelSha256, engine.currentMappings(), engine.config.contextFrames)
		val response = ModelHistoryResponseT().apply {
			this.requestId = requestId
			version = manager.history.version
			entries = statuses.map { status -> AIModelHistoryEntryT().apply {
				modelSha256 = status.entry.modelSha256; modelId = status.entry.modelId; modelVersion = status.entry.modelVersion
				displayName = status.entry.displayName; kind = if (status.entry.kind == ModelArtifactKind.PERSONAL) AIModelKind.PERSONAL else AIModelKind.GLOBAL
				profileId = status.entry.profileId.orEmpty(); lastUsedEpochMillis = status.entry.lastUsedEpochMillis; pinned = status.entry.pinned
				compatible = status.compatible; compatibilityError = status.compatibilityError.orEmpty(); validated = status.validated
				active = status.active; rollbackTarget = status.rollbackTarget
			} }.toTypedArray()
			rollbackModelSha256 = statuses.firstOrNull { it.rollbackTarget && it.compatible }?.entry?.modelSha256.orEmpty()
			errorCode = AIModelErrorCode.OK; error = ""
		}
		val fbb = FlatBufferBuilder(1024)
		val offset = ModelHistoryResponse.pack(fbb, response)
		fbb.finish(createRPCMessage(fbb, RpcMessage.ModelHistoryResponse, offset, header))
		conn.send(fbb.dataBuffer())
	}

	private fun sendProgress(conn: GenericConnection, header: RpcMessageHeader, requestId: String, progress: ModelDownloadProgress, error: Throwable? = null) {
		val total = progress.bytesTotal.coerceAtLeast(0)
		val response = ModelOperationProgressT().apply {
			this.requestId = requestId; operation = AIModelOperation.DOWNLOAD
			stage = if (error != null) AIModelProgressStage.FAILED else progress.stage.toRpc()
			bytesCompleted = progress.bytesCompleted.coerceAtLeast(0); bytesTotal = total
			this.progress = if (total == 0L) 0f else (bytesCompleted.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
			modelSha256 = progress.modelSha256; errorCode = error?.toRpcError() ?: AIModelErrorCode.OK; this.error = error?.message.orEmpty()
		}
		val fbb = FlatBufferBuilder(256)
		val offset = ModelOperationProgress.pack(fbb, response)
		fbb.finish(createRPCMessage(fbb, RpcMessage.ModelOperationProgress, offset, header))
		conn.send(fbb.dataBuffer())
	}

	private fun sendFailure(conn: GenericConnection, header: RpcMessageHeader, requestId: String, operation: Int, error: Throwable) =
		sendAction(conn, header, requestId, operation, false, errorCode = error.toRpcError(), error = error.message ?: error.javaClass.simpleName)

	private fun sendAction(
		conn: GenericConnection,
		header: RpcMessageHeader,
		requestId: String,
		operation: Int,
		success: Boolean,
		modelSha256: String = "",
		provider: ExecutionProviderType? = null,
		errorCode: Int = AIModelErrorCode.OK,
		error: String = "",
	) {
		val response = ModelActionResponseT().apply {
			this.requestId = requestId; this.operation = operation; this.success = success; this.modelSha256 = modelSha256
			activeProvider = (provider ?: engine.currentProvider ?: ExecutionProviderType.AUTO).toRpc(); this.errorCode = errorCode; this.error = error
		}
		val fbb = FlatBufferBuilder(256)
		val offset = ModelActionResponse.pack(fbb, response)
		fbb.finish(createRPCMessage(fbb, RpcMessage.ModelActionResponse, offset, header))
		conn.send(fbb.dataBuffer())
	}

	private fun createRPCMessage(fbb: FlatBufferBuilder, messageType: Byte, messageOffset: Int, respondTo: RpcMessageHeader): Int {
		if (rpcHandler != null) return rpcHandler.createRPCMessage(fbb, messageType, messageOffset, respondTo)
		RpcMessageHeader.startRpcMessageHeader(fbb)
		RpcMessageHeader.addMessage(fbb, messageOffset)
		RpcMessageHeader.addMessageType(fbb, messageType)
		respondTo.txId()?.id()?.let { RpcMessageHeader.addTxId(fbb, TransactionId.createTransactionId(fbb, it)) }
		val rpcHeader = RpcMessageHeader.endRpcMessageHeader(fbb)
		val messages = MessageBundle.createRpcMsgsVector(fbb, intArrayOf(rpcHeader))
		MessageBundle.startMessageBundle(fbb); MessageBundle.addRpcMsgs(fbb, messages)
		return MessageBundle.endMessageBundle(fbb)
	}

	private fun requiredPath(value: String?, field: String): Path = value?.takeIf(String::isNotBlank)?.let(Path::of)
		?: throw IllegalArgumentException("$field is required")

	private fun executionProvider(value: Int): ExecutionProviderType = when (value) {
		AIExecutionProvider.AUTO -> ExecutionProviderType.AUTO
		AIExecutionProvider.CPU -> ExecutionProviderType.CPU
		AIExecutionProvider.CUDA -> ExecutionProviderType.CUDA
		AIExecutionProvider.TENSORRT -> ExecutionProviderType.TENSORRT
		AIExecutionProvider.DIRECTML -> ExecutionProviderType.DIRECTML
		else -> throw IllegalArgumentException("Unknown execution provider")
	}

	private fun ExecutionProviderType.toRpc(): Int = when (this) {
		ExecutionProviderType.AUTO -> AIExecutionProvider.AUTO
		ExecutionProviderType.CPU -> AIExecutionProvider.CPU
		ExecutionProviderType.CUDA -> AIExecutionProvider.CUDA
		ExecutionProviderType.TENSORRT -> AIExecutionProvider.TENSORRT
		ExecutionProviderType.DIRECTML -> AIExecutionProvider.DIRECTML
	}

	private fun Throwable.toRpcError(): Int = when (this) {
		is IllegalArgumentException -> AIModelErrorCode.INVALID_ARGUMENT
		is ManagedModelException -> when (code) {
			ManagedModelErrorCode.INVALID_ARGUMENT -> AIModelErrorCode.INVALID_ARGUMENT
			ManagedModelErrorCode.PATH_REJECTED -> AIModelErrorCode.PATH_REJECTED
			ManagedModelErrorCode.FILE_NOT_FOUND -> AIModelErrorCode.FILE_NOT_FOUND
			ManagedModelErrorCode.MODEL_INTEGRITY -> AIModelErrorCode.MODEL_INTEGRITY
			ManagedModelErrorCode.IO_ERROR -> AIModelErrorCode.IO_ERROR
		}
		is ModelCatalogException -> toRpcError()
		is ModelLoadException -> when (code) {
			ModelLoadErrorCode.PROVIDER_NOT_PACKAGED, ModelLoadErrorCode.PROVIDER_UNAVAILABLE -> AIModelErrorCode.PROVIDER_UNAVAILABLE
			ModelLoadErrorCode.SIDECAR_INVALID, ModelLoadErrorCode.FEATURE_SCHEMA_MISMATCH, ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH -> AIModelErrorCode.METADATA_INVALID
			ModelLoadErrorCode.MODEL_INTEGRITY -> AIModelErrorCode.MODEL_INTEGRITY
			else -> AIModelErrorCode.ACTIVATION_FAILED
		}
		else -> AIModelErrorCode.INTERNAL_ERROR
	}

	private fun ModelCatalogException.toRpcError(): Int = when (code) {
		ModelCatalogErrorCode.CATALOG_INVALID -> AIModelErrorCode.CATALOG_INVALID
		ModelCatalogErrorCode.URL_REJECTED -> AIModelErrorCode.URL_REJECTED
		ModelCatalogErrorCode.DOWNLOAD_FAILED -> AIModelErrorCode.DOWNLOAD_FAILED
		ModelCatalogErrorCode.MODEL_NOT_FOUND -> AIModelErrorCode.MODEL_NOT_FOUND
		ModelCatalogErrorCode.MODEL_INTEGRITY -> AIModelErrorCode.MODEL_INTEGRITY
	}

	private fun ModelDownloadStage.toRpc(): Int = when (this) {
		ModelDownloadStage.DOWNLOADING_MODEL -> AIModelProgressStage.DOWNLOADING_MODEL
		ModelDownloadStage.DOWNLOADING_METADATA -> AIModelProgressStage.DOWNLOADING_METADATA
		ModelDownloadStage.VERIFYING -> AIModelProgressStage.VERIFYING
		ModelDownloadStage.IMPORTING -> AIModelProgressStage.IMPORTING
		ModelDownloadStage.COMPLETED -> AIModelProgressStage.COMPLETED
	}

	private fun AIRuntimeStatus.toRpc(requestId: String) = ModelRuntimeStatusResponseT().apply {
		this.requestId = requestId
		health = when (this@toRpc.health) {
			AIRuntimeHealthState.DISABLED -> AIRuntimeRpcHealth.DISABLED
			AIRuntimeHealthState.UNAVAILABLE -> AIRuntimeRpcHealth.UNAVAILABLE
			AIRuntimeHealthState.HEALTHY -> AIRuntimeRpcHealth.HEALTHY
			AIRuntimeHealthState.DEGRADED -> AIRuntimeRpcHealth.DEGRADED
			AIRuntimeHealthState.WATCHDOG_TRIPPED -> AIRuntimeRpcHealth.WATCHDOG_TRIPPED
		}
		loadState = when (this@toRpc.loadState) {
			AIModelLoadState.RUNTIME_UNAVAILABLE -> AIModelRpcLoadState.RUNTIME_UNAVAILABLE
			AIModelLoadState.UNLOADED -> AIModelRpcLoadState.UNLOADED
			AIModelLoadState.LOADING -> AIModelRpcLoadState.LOADING
			AIModelLoadState.ACTIVE -> AIModelRpcLoadState.ACTIVE
			AIModelLoadState.ERROR -> AIModelRpcLoadState.ERROR
		}
		activeModelId = activeModel?.modelId.orEmpty(); activeModelVersion = activeModel?.modelVersion.orEmpty(); activeModelSha256 = activeModel?.modelSha256.orEmpty()
		activeProvider = (activeModel?.provider ?: ExecutionProviderType.AUTO).toRpc()
		configuration = AIModelConfigurationStateT().apply {
			enabled = engine.config.enabled
			requestedProvider = engine.config.requestedProvider.toRpc()
			contextFrames = engine.config.contextFrames
			confidenceThreshold = engine.config.confidenceThreshold
			maximumCorrectionRadians = engine.config.maximumCorrectionRadians
			maximumRateRadiansPerSecond = engine.config.maximumAngularRateRadiansPerSecond
			maximumAccelerationRadiansPerSecondSquared = engine.config.maximumAngularAccelerationRadiansPerSecondSquared
			smoothing = engine.config.smoothing
			staleDecaySeconds = engine.config.staleDecaySeconds
			legacyMode = when (engine.config.legacyDriftCompensationMode) {
				LegacyDriftCompensationMode.REPLACE -> AILegacyDriftMode.REPLACE
				LegacyDriftCompensationMode.COMPOSE -> AILegacyDriftMode.COMPOSE
			}
			mappings = engine.currentMappings().map { mapping -> AITrackerSlotMappingT().apply {
				trackerId = mapping.trackerId.toLong(); bodyRoleId = mapping.bodyRoleId; slot = mapping.slot
			} }.toTypedArray()
		}
		runtimeFlavor = activeModel?.runtimeFlavor.orEmpty(); runtimeVersion = activeModel?.runtimeVersion.orEmpty()
		metrics = AIModelRuntimeMetricsT().apply {
			inferenceLatency = this@toRpc.metrics.latency.toRpc(); queueWaitLatency = this@toRpc.metrics.queueWaitLatency.toRpc(); safetyGateLatency = this@toRpc.metrics.safetyGateLatency.toRpc()
			queueDepth = this@toRpc.metrics.queueDepth.toLong(); queueDrops = this@toRpc.metrics.queueDrops; processedInferences = this@toRpc.metrics.processedInferences
			inferenceRateHz = this@toRpc.metrics.inferenceRateHz; staleResults = this@toRpc.metrics.staleResults; inferenceErrors = this@toRpc.metrics.inferenceErrors; outliers = this@toRpc.metrics.outliers
			confidenceMinimum = this@toRpc.metrics.confidenceMinimum ?: 0f; confidenceMean = this@toRpc.metrics.confidenceMean ?: 0f; confidenceMaximum = this@toRpc.metrics.confidenceMaximum ?: 0f
		}
		trackers = this@toRpc.trackers.map { tracker -> AITrackerRuntimeStateT().apply {
			trackerId = tracker.trackerId.toLong(); bodyRoleId = tracker.bodyRoleId; slot = tracker.slot; epoch = tracker.epoch ?: 0
			confidence = tracker.confidence ?: 0f; correctionApplied = tracker.correctionApplied; rejectionReason = tracker.rejectionReason.orEmpty()
		} }.toTypedArray()
		lastErrorCode = this@toRpc.lastError?.code.orEmpty(); lastError = this@toRpc.lastError?.message.orEmpty()
		inferenceReady = this@toRpc.inferenceReady
		inferenceReadyModelSha256 = this@toRpc.inferenceReadyModelSha256.orEmpty()
		readinessBlockingReason = when (this@toRpc.readinessBlockingReason) {
			"NO_ACTIVE_MODEL" -> AIReadinessBlockingReason.NO_ACTIVE_MODEL
			"ACTIVE_CORRECTION_OPT_IN_DISABLED" -> AIReadinessBlockingReason.ACTIVE_CORRECTION_OPT_IN_DISABLED
			"INFERENCE_READY_GATE_PENDING" -> AIReadinessBlockingReason.INFERENCE_READY_GATE_PENDING
			"INFERENCE_READY_MODEL_MISMATCH" -> AIReadinessBlockingReason.INFERENCE_READY_MODEL_MISMATCH
			"RUNTIME_UNAVAILABLE" -> AIReadinessBlockingReason.RUNTIME_UNAVAILABLE
			"WATCHDOG_TRIPPED" -> AIReadinessBlockingReason.WATCHDOG_TRIPPED
			"SHADOW_MODE" -> AIReadinessBlockingReason.SHADOW_MODE
			else -> AIReadinessBlockingReason.NONE
		}
		readinessDetail = this@toRpc.readinessDetail
		effectiveCorrectionEnabled = this@toRpc.effectiveCorrectionEnabled
	}

	private fun InferenceLatencyPercentiles.toRpc() = AILatencyPercentilesT().also {
		it.p50Micros = p50Micros; it.p95Micros = p95Micros; it.p99Micros = p99Micros
	}

	private infix fun Long.has(flag: Long): Boolean = this and flag != 0L

	companion object {
		const val ENABLED = 1L shl 0
		const val PROVIDER = 1L shl 1
		const val CONTEXT = 1L shl 2
		const val CONFIDENCE = 1L shl 3
		const val LIMITS = 1L shl 4
		const val SMOOTHING = 1L shl 5
		const val STALE_DECAY = 1L shl 6
		const val LEGACY_MODE = 1L shl 7
		const val MAPPINGS = 1L shl 8
		const val CONFIG_ALL = ENABLED or PROVIDER or CONTEXT or CONFIDENCE or LIMITS or SMOOTHING or STALE_DECAY or LEGACY_MODE or MAPPINGS
	}
}
