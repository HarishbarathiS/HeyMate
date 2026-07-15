package com.harish.heymate.agent

/** A request to the reasoning agent: what was said, optionally with a photo. */
data class AgentRequest(
    val transcript: String,
    /** Absolute path of a photo to include (vision), or null for voice-only. */
    val photoPath: String? = null,
)

data class AgentReply(val text: String)

/** Pluggable reasoning backend. */
interface AgentClient {
    /** Returns the agent's reply, or a failure with a human-readable message. */
    suspend fun reason(request: AgentRequest): Result<AgentReply>
}
