package com.rs.ocportable

/**
 * One decoded SSE frame. Kept as a plain map so the status machine is testable without Android's
 * `org.json` implementation.
 */
data class EngineEvent(val type: String, val data: Map<String, Any?>)
