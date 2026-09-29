package com.school.smartcbt.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val examId: String,
    val content: String,
    val options: String, // JSON array of options
    val type: String,
    val orderNum: Int,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    var selectedAnswer: String? = null // Autosave local answer
)
