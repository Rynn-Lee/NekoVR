package dev.slimevr.updater

import io.eiren.util.logging.LogManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class UpdateInfo(
	val isUpdateAvailable: Boolean = false,
	val currentVersion: String = "0.3.2",
	val latestVersion: String = "0.3.2",
	val changelog: String = "",
	val downloadUrl: String = "",
	val branch: String = "main",
)

class AutoUpdater(
	private val repoOwner: String = "Rynn-Lee",
	private val repoName: String = "NekoVR",
	val currentVersion: String = "0.3.2",
) {

	private val httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(6))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	var cachedUpdateInfo: UpdateInfo = UpdateInfo(currentVersion = currentVersion, latestVersion = currentVersion)
		private set

	fun checkForUpdates(): UpdateInfo {
		try {
			val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
			val request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("User-Agent", "NekoVR-Client")
				.timeout(Duration.ofSeconds(6))
				.GET()
				.build()

			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() == 200) {
				val jsonElement = Json.parseToJsonElement(response.body()).jsonObject
				val tagName = jsonElement["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: currentVersion
				val body = jsonElement["body"]?.jsonPrimitive?.content ?: ""
				val htmlUrl = jsonElement["html_url"]?.jsonPrimitive?.content ?: ""

				val isNewer = isVersionNewer(currentVersion, tagName)
				cachedUpdateInfo = UpdateInfo(
					isUpdateAvailable = isNewer,
					currentVersion = currentVersion,
					latestVersion = tagName,
					changelog = body,
					downloadUrl = htmlUrl,
				)
				if (isNewer) {
					LogManager.info("[Updater] Found new NekoVR release: $tagName (Current: $currentVersion)")
				}
			} else {
				// Fallback to checking latest commit in main branch
				val commitReq = HttpRequest.newBuilder()
					.uri(URI.create("https://api.github.com/repos/$repoOwner/$repoName/commits/main"))
					.header("User-Agent", "NekoVR-Client")
					.timeout(Duration.ofSeconds(6))
					.GET()
					.build()
				val commitRes = httpClient.send(commitReq, HttpResponse.BodyHandlers.ofString())
				if (commitRes.statusCode() == 200) {
					val commitObj = Json.parseToJsonElement(commitRes.body()).jsonObject
					val sha = commitObj["sha"]?.jsonPrimitive?.content?.take(7) ?: "main"
					val commitMsg = commitObj["commit"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ""
					cachedUpdateInfo = UpdateInfo(
						isUpdateAvailable = false,
						currentVersion = currentVersion,
						latestVersion = "$currentVersion ($sha)",
						changelog = commitMsg,
						downloadUrl = "https://github.com/$repoOwner/$repoName/tree/main",
					)
				}
			}
		} catch (e: Exception) {
			LogManager.warning("[Updater] Check for updates failed: ${e.message}")
		}
		return cachedUpdateInfo
	}

	private fun isVersionNewer(current: String, latest: String): Boolean {
		return try {
			val curParts = current.split(".").map { it.toIntOrNull() ?: 0 }
			val latParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
			for (i in 0 until maxOf(curParts.size, latParts.size)) {
				val c = curParts.getOrElse(i) { 0 }
				val l = latParts.getOrElse(i) { 0 }
				if (l > c) return true
				if (l < c) return false
			}
			false
		} catch (_: Exception) {
			false
		}
	}
}
