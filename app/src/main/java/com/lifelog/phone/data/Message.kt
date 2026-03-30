package com.lifelog.phone.data

data class Message(
    val id: Long = 0,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val stem: String = ""
)
