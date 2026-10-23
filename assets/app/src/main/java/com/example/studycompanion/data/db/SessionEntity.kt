package com.example.studycompanion.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.studycompanion.domain.models.Session

@Entity(
    tableName = "sessions",
    indices = [Index("startDate")]
)
data class SessionEntity(
    @PrimaryKey val id: Long,
    val subject: String,
    val start: String,
    val end: String,
    val durationSec: Int,
    val device: String,
    val startDate: String
) {
    fun toDomain(): Session = Session(
        id = id,
        subject = subject,
        start = start,
        end = end,
        durationSec = durationSec,
        device = device,
        startDate = startDate
    )

    companion object {
        fun fromDomain(session: Session): SessionEntity = SessionEntity(
            id = session.id,
            subject = session.subject,
            start = session.start,
            end = session.end,
            durationSec = session.durationSec,
            device = session.device,
            startDate = session.startDate.ifEmpty {
                if (session.start.length >= 10) session.start.substring(0, 10) else ""
            }
        )
    }
}
