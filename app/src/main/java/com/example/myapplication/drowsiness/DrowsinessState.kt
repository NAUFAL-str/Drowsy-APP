// drowsiness/DrowsinessState.kt
package com.example.myapplication.drowsiness

data class DrowsinessState(
    val status: String = "Initializing...",
    val probability: Float = 0f,
    val featureCount: Int = 0
)