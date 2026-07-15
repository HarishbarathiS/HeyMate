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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends transcripts (and optionally photos) to the user's Hermes agent endpoint.
 *
 * ============================================================================
 * TODO(HERMES FORMAT): the exact request/response schema is PENDING — the user
 * will provide it. Everything below the marked lines is a provisional contract:
 *
 *   POST <endpoint>
 *   { "input": "<transcript>", "image_base64": "<jpeg…>" | absent }
 *   → response body: { "output": "<reply text>" }  (or raw text fallback)
 *
 * When the real format arrives, ONLY [buildRequestJson] and [parseReply] need
 * to change — the rest of the pipeline (capture → transcript → here → TTS/UI)
 * is final.
 * ============================================================================
 */
class HermesAgentClient(private val prefs: Prefs) : AgentClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun reason(request: AgentRequest): Result<AgentReply> = withContext(Dispatchers.IO) {
        val endpoint = prefs.hermesEndpointNow()
        if (endpoint.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Hermes endpoint not configured — set it in Settings")
            )
        }

        runCatching {
            val body = buildRequestJson(request).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()

            http.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Hermes returned HTTP ${response.code}")
                }
                val raw = response.body?.string().orEmpty()
                AgentReply(parseReply(raw))
            }
        }.onFailure { Log.w(TAG, "Hermes call failed: ${it.message}") }
    }

    // ----- TODO(HERMES FORMAT): provisional request schema — replace when format is known -----
    private fun buildRequestJson(request: AgentRequest): JSONObject {
        val json = JSONObject()
        json.put("input", request.transcript)
        request.photoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                json.put("image_base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            }
        }
        return json
    }

    // ----- TODO(HERMES FORMAT): provisional response parsing — replace when format is known -----
    private fun parseReply(raw: String): String {
        return runCatching { JSONObject(raw).optString("output").ifBlank { raw } }
            .getOrDefault(raw)
            .trim()
            .ifBlank { "(empty reply from agent)" }
    }

    companion object {
        private const val TAG = "HermesAgentClient"
    }
}
