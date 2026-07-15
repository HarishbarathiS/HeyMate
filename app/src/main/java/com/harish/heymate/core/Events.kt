package com.harish.heymate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Everything the glasses/agent pipeline does is surfaced as an event on the feed. */
sealed class AppEvent {
    abstract val id: Long
    abstract val time: Long

    data class Transcript(override val id: Long, override val time: Long, val text: String) : AppEvent()
    data class AgentReply(override val id: Long, override val time: Long, val text: String) : AppEvent()
    data class PhotoCaptured(override val id: Long, override val time: Long, val path: String) : AppEvent()
    data class VoiceStart(override val id: Long, override val time: Long) : AppEvent()
    data class VoiceEnd(override val id: Long, override val time: Long) : AppEvent()
    data class Status(override val id: Long, override val time: Long, val text: String) : AppEvent()
    data class Error(override val id: Long, override val time: Long, val text: String) : AppEvent()
}

/** In-memory feed of pipeline events, newest first. */
object EventFeed {
    private const val MAX_EVENTS = 300
    private val counter = AtomicLong(0)

    private val _events = MutableStateFlow<List<AppEvent>>(emptyList())
    val events: StateFlow<List<AppEvent>> = _events.asStateFlow()

    private fun push(build: (id: Long, time: Long) -> AppEvent) {
        val event = build(counter.incrementAndGet(), System.currentTimeMillis())
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
    }

    fun transcript(text: String) = push { id, t -> AppEvent.Transcript(id, t, text) }
    fun agentReply(text: String) = push { id, t -> AppEvent.AgentReply(id, t, text) }
    fun photo(path: String) = push { id, t -> AppEvent.PhotoCaptured(id, t, path) }
    fun voiceStart() = push { id, t -> AppEvent.VoiceStart(id, t) }
    fun voiceEnd() = push { id, t -> AppEvent.VoiceEnd(id, t) }
    fun status(text: String) = push { id, t -> AppEvent.Status(id, t, text) }
    fun error(text: String) = push { id, t -> AppEvent.Error(id, t, text) }
}
