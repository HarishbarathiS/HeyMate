package com.harish.heymate.agent

import android.util.Base64
import android.util.Log
import com.harish.heymate.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends transcripts (and optionally photos) to Google's Gemini API for both
 * reasoning (text) and vision (image understanding) in a single multimodal call.
 *
 * TEMPORARY: swapped in for the Hermes endpoint until the Hermes format arrives.
 * Uses the Generative Language REST API:
 *
 *   POST https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent?key=<API_KEY>
 *   {
 *     "contents": [{
 *       "parts": [
 *         { "text": "<transcript>" },
 *         { "inline_data": { "mime_type": "image/jpeg", "data": "<base64>" } }  // optional
 *       ]
 *     }]
 *   }
 *   → { "candidates": [{ "content": { "parts": [{ "text": "<reply>" }] } }] }
 *
 * Only [buildRequestJson] and [parseReply] are Gemini-specific — the rest of the
 * pipeline (capture → transcript → here → TTS/UI) is unchanged.
 */
class GeminiAgentClient(private val prefs: Prefs) : AgentClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun reason(request: AgentRequest): Result<AgentReply> = withContext(Dispatchers.IO) {
        val apiKey = prefs.geminiApiKeyNow()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key not configured — set it in Settings")
            )
        }

        runCatching {
            val url = "$ENDPOINT_BASE$MODEL:generateContent?key=$apiKey"
            val body = buildRequestJson(request).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val httpRequest = Request.Builder()
                .url(url)
                .post(body)
                .build()

            http.newCall(httpRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Gemini returned HTTP ${response.code}: ${parseError(raw)}")
                }
                AgentReply(parseReply(raw))
            }
        }.onFailure { Log.w(TAG, "Gemini call failed: ${it.message}") }
    }

    private fun buildRequestJson(request: AgentRequest): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", request.transcript))

        request.photoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val inlineData = JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                parts.put(JSONObject().put("inline_data", inlineData))
            }
        }

        val content = JSONObject().put("parts", parts)
        return JSONObject().put("contents", JSONArray().put(content))
    }

    private fun parseReply(raw: String): String {
        return runCatching {
            JSONObject(raw)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }.getOrDefault(raw)
            .trim()
            .ifBlank { "(empty reply from Gemini)" }
    }

    private fun parseError(raw: String): String =
        runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
            .getOrDefault(raw)

    companion object {
        private const val TAG = "GeminiAgentClient"
        private const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        /** Multimodal model — handles both reasoning and vision. */
        private const val MODEL = "gemini-2.5-flash"
    }
}
