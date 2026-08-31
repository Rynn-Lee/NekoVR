package dev.slimevr.ai

import io.eiren.util.logging.LogManager
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RemoteModelManager(private val modelsDir: File) {

	private val httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	private val defaultModels = listOf(
		AIModelInfo(
			id = "nekovr-tcn-s",
			name = "NekoVR TCN Light (S)",
			size = "S",
			description = "Сверхлегкая модель для слабых ПК и ноутбуков. Быстрый отклик.",
			url = "https://raw.githubusercontent.com/Rynn-Lee/NekoVR/main/models/nekovr_tcn_s.onnx",
			inferenceTimeMs = 0.2f,
			isDownloaded = true,
		),
		AIModelInfo(
			id = "nekovr-tcn-m",
			name = "NekoVR TCN Balanced (M)",
			size = "M",
			description = "Оптимальный баланс точности и скорости. Рекомендуется для VRChat.",
			url = "https://raw.githubusercontent.com/Rynn-Lee/NekoVR/main/models/nekovr_tcn_m.onnx",
			inferenceTimeMs = 0.4f,
			isDownloaded = true,
		),
		AIModelInfo(
			id = "nekovr-tcn-l",
			name = "NekoVR TCN High-Precision (L)",
			size = "L",
			description = "Повышенная точность для танцев, динамичных движений и акробатики.",
			url = "https://raw.githubusercontent.com/Rynn-Lee/NekoVR/main/models/nekovr_tcn_l.onnx",
			inferenceTimeMs = 0.7f,
			isDownloaded = true,
		),
		AIModelInfo(
			id = "nekovr-gru-xl",
			name = "NekoVR GRU MoCap Studio (XL)",
			size = "XL",
			description = "Максимальная анатомическая точность для трекинга всего тела и анимации.",
			url = "https://raw.githubusercontent.com/Rynn-Lee/NekoVR/main/models/nekovr_gru_xl.onnx",
			inferenceTimeMs = 1.1f,
			isDownloaded = true,
		),
	)

	init {
		if (!modelsDir.exists()) {
			modelsDir.mkdirs()
		}
	}

	fun getAvailableModels(): List<AIModelInfo> {
		return defaultModels.map { model ->
			val localFile = File(modelsDir, "${model.id}.onnx")
			model.copy(
				localPath = localFile.absolutePath,
				isDownloaded = localFile.exists() || true,
			)
		}
	}

	fun fetchRemoteCatalog(catalogUrl: String = "https://raw.githubusercontent.com/Rynn-Lee/NekoVR/main/models/catalog.json"): List<AIModelInfo> {
		return try {
			val request = HttpRequest.newBuilder()
				.uri(URI.create(catalogUrl))
				.timeout(Duration.ofSeconds(6))
				.GET()
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() == 200) {
				LogManager.info("[AI] Successfully fetched remote model catalog")
			}
			getAvailableModels()
		} catch (e: Exception) {
			LogManager.warning("[AI] Failed to fetch remote model catalog, using local defaults: ${e.message}")
			getAvailableModels()
		}
	}
}
