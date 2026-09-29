package com.school.smartcbt.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CbtDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Query("SELECT * FROM questions WHERE examId = :examId ORDER BY orderNum ASC")
    suspend fun getQuestionsByExamId(examId: String): List<QuestionEntity>

    @Query("UPDATE questions SET selectedAnswer = :answer WHERE id = :questionId")
    suspend fun updateAnswer(questionId: String, answer: String)
    
    @Query("DELETE FROM questions WHERE examId = :examId")
    suspend fun deleteExamData(examId: String)
}
