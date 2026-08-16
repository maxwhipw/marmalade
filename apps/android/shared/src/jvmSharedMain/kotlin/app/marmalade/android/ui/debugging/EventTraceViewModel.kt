package app.marmalade.android.ui.debugging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * ViewModel for [EventTraceScreen] — the read side of the persistent
 * gateway_events ring buffer (written by MessageStream.recordToRingBuffer,
 * ~500 rows/session). Distinct from the Debug tab's in-memory transport log:
 * this survives process death and is scoped per session.
 *
 * Plain multiplatform [ViewModel] in `:shared`, no Hilt (repo constraint).
 * The [ChatDao] is constructor-injected rather than reached through an
 * `Application`: this VM is pure read-side Room + Flow, so the only Android
 * coupling it ever had was the database handle, and the *host* already knows
 * it (see [CronViewModel][app.marmalade.android.ui.settings.CronViewModel]
 * for the same shape with the RPC).
 */
class EventTraceViewModel(private val chatDao: ChatDao) : ViewModel() {

    /** Selected session key filter; null = all sessions. */
    private val _sessionFilter = MutableStateFlow<String?>(null)
    val sessionFilter: StateFlow<String?> = _sessionFilter.asStateFlow()

    /** Case-insensitive substring filter over the event type. */
    private val _typeFilter = MutableStateFlow("")
    val typeFilter: StateFlow<String> = _typeFilter.asStateFlow()

    /** Session rows for the filter dropdown. */
    val sessions: StateFlow<List<SessionEntity>> = chatDao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<GatewayEventEntity>> = combine(
        _sessionFilter.flatMapLatest { key ->
            if (key == null) {
                chatDao.observeRecentGatewayEvents(EVENT_TRACE_LIMIT)
            } else {
                chatDao.observeGatewayEventsForSession(key, EVENT_TRACE_LIMIT)
            }
        },
        _typeFilter,
    ) { rows, filter -> filterEventsByType(rows, filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSessionFilter(key: String?) {
        _sessionFilter.value = key
    }

    fun setTypeFilter(query: String) {
        _typeFilter.value = query
    }

    companion object {
        /** Factory for `viewModel(factory = EventTraceViewModel.factory(dao))`. */
        fun factory(chatDao: ChatDao) = viewModelFactory {
            initializer { EventTraceViewModel(chatDao) }
        }
    }
}

/*
 * The four declarations below are public, not `internal`: they are consumed
 * from `:app` — EventTraceScreen (main) reads [EVENT_TRACE_LIMIT] and calls
 * [prettyPayload]; EventTraceLogicTest (test) exercises the pure helpers. A
 * module's `internal` is invisible to BOTH, since `:app`'s test compilation is
 * a friend of `:app`'s main one only — never of `:shared`'s. That is the
 * "internal trap" this module has hit before (see the 2026-07-25 KMP handoff
 * and combineDateAndTime in CronSettingsScreen.kt).
 */

/** Newest-first window size — matches the per-session ring-buffer cap. */
const val EVENT_TRACE_LIMIT = 500

/** Payloads longer than this render truncated; copy still yields the full raw string. */
const val EVENT_TRACE_DISPLAY_CAP = 4_000

/** Pure type-substring filter (case-insensitive); blank query passes everything. */
fun filterEventsByType(
    rows: List<GatewayEventEntity>,
    query: String,
): List<GatewayEventEntity> {
    val q = query.trim()
    if (q.isEmpty()) return rows
    return rows.filter { it.type.contains(q, ignoreCase = true) }
}

/**
 * Pretty-print a stored payload for display: valid JSON re-encodes indented,
 * anything else (including the literal "null" written for payload-less
 * events) renders as-is. Output is capped at [EVENT_TRACE_DISPLAY_CAP]
 * chars with a truncation marker — copy uses the raw string instead.
 */
fun prettyPayload(raw: String, json: Json): String {
    val pretty = runCatching {
        val element = json.parseToJsonElement(raw)
        prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    }.getOrDefault(raw)
    return if (pretty.length > EVENT_TRACE_DISPLAY_CAP) {
        pretty.take(EVENT_TRACE_DISPLAY_CAP) + "\n… truncated (${pretty.length} chars — copy for the full payload)"
    } else {
        pretty
    }
}

private val prettyJson = Json { prettyPrint = true }
