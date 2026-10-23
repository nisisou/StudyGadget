package com.example.studycompanion.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.studycompanion.domain.models.Subject

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey val id: String,
    val label: String,
    val colorHex: String,
    val sortOrder: Int,
    val archived: Boolean = false
) {
    fun toDomain(): Subject = Subject(
        id = id,
        label = label,
        colorHex = colorHex,
        sortOrder = sortOrder,
        archived = archived
    )

    companion object {
        fun fromDomain(subject: Subject): SubjectEntity = SubjectEntity(
            id = subject.id,
            label = subject.label,
            colorHex = subject.colorHex,
            sortOrder = subject.sortOrder,
            archived = subject.archived
        )
    }
}
