package com.emberheat.data.group

import kotlinx.serialization.Serializable

@Serializable
data class HeaterGroup(
    val id: String,
    val name: String,
    val memberMacs: List<String>,
)
