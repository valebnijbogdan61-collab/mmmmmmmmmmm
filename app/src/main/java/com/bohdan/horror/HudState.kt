package com.bohdan.horror

// Незмінний "знімок" стану для інтерфейсу. Створюється в потоці гри, інтерфейс лише читає.
class HudState(
    val prompt: String?,
    val msg: String, val msgT: Float,
    val fade: Float, val flash: Float,
    val bigText: String, val bigT: Float,
    val keyTimer: Float, val keyPhase: Boolean,
    val holding: Boolean, val charging: Boolean, val charge: Float,
    val showF: Boolean, val showC: Boolean,
    val phoneOpen: Boolean, val phoneNum: String, val phoneLines: List<String>,
    val dead: Boolean, val deadText: String, val endScreen: Boolean
)
