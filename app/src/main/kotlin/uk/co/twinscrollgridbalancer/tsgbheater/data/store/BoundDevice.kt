package uk.co.twinscrollgridbalancer.tsgbheater.data.store

import kotlinx.serialization.Serializable

@Serializable
data class BoundDevice(
    val mac: String,
    val name: String,
    val lastSeenAtMs: Long,
    val autoStartStopEnabled: Boolean = false,
)
