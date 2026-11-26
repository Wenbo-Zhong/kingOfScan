package com.kingscan.app.core.document

import kotlinx.serialization.Serializable

@Serializable
enum class CompositionKind { Grid, Vertical, Horizontal, IdCard, Overlap }

@Serializable
data class CompositionOptions(
    val kind: CompositionKind = CompositionKind.Grid,
    val columns: Int = 2,
    val gap: Int = 24,
    val outputWidth: Int = 1600,
)
