package com.example.studycompanion.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Provider

@Database(
    entities = [SessionEntity::class, SubjectEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun subjectDao(): SubjectDao

    companion object {
        val DEFAULT_SUBJECTS = listOf(
            SubjectEntity("english", "ENGLISH", "60A5FA", 0, false),
            SubjectEntity("math", "MATH", "F59E0B", 1, false),
            SubjectEntity("japanese", "JAPANESE", "F472B6", 2, false)
        )

        fun getCallback(subjectDaoProvider: Provider<SubjectDao>): Callback {
            return object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    CoroutineScope(Dispatchers.IO).launch {
                        subjectDaoProvider.get().insertSubjects(DEFAULT_SUBJECTS)
                    }
                }
            }
        }
    }
}
