package org.schabi.newpipe.gemini.obj

import java.io.Serializable

data class SubtitleBlock(
    val sequenceNumber: Int,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val timeCode: String,
    val text: String
) : Serializable {
    fun toSrt(): String = "$sequenceNumber\n$timeCode\n$text"
}
