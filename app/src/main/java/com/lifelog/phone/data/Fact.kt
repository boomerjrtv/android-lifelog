package com.lifelog.phone.data

data class Fact(
    val id: Long = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
