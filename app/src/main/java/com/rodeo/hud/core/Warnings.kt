package com.rodeo.hud.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Severity { INFO, WARN, DANGER }

data class Warning(
    /** Stable key so the same source replaces its own warning instead of stacking. */
    val key: String,
    val title: String,
    val detail: String = "",
    val severity: Severity = Severity.WARN,
    val time: Long = System.currentTimeMillis(),
)

/** One place every feature posts rider-relevant warnings to; the HUD shows the top one. */
class WarningCenter {
    private val _warnings = MutableStateFlow<List<Warning>>(emptyList())
    val warnings: StateFlow<List<Warning>> = _warnings.asStateFlow()
    private val dismissed = mutableMapOf<String, Long>()

    fun post(w: Warning) {
        // A dismissed warning stays quiet for 10 minutes unless it escalates to DANGER.
        val until = dismissed[w.key]
        if (until != null && System.currentTimeMillis() < until && w.severity != Severity.DANGER) return
        _warnings.value = (_warnings.value.filter { it.key != w.key } + w)
            .sortedWith(compareByDescending<Warning> { it.severity.ordinal }.thenByDescending { it.time })
    }

    fun clear(key: String) {
        _warnings.value = _warnings.value.filter { it.key != key }
    }

    fun dismissTop() {
        val top = _warnings.value.firstOrNull() ?: return
        dismissed[top.key] = System.currentTimeMillis() + 10 * 60_000
        clear(top.key)
    }
}
