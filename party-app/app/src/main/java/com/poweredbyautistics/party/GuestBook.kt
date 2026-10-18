package com.poweredbyautistics.party

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One guest-book entry. Re-entering a name replaces it, which moves it to the front. */
@Entity(tableName = "guests")
data class Guest(@PrimaryKey val name: String, val addedAt: Long = System.currentTimeMillis())

@Dao
interface GuestDao {
    @Query("SELECT * FROM guests ORDER BY addedAt DESC")
    fun all(): Flow<List<Guest>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(guest: Guest)
}

@Database(entities = [Guest::class], version = 1)
abstract class GuestBookDb : RoomDatabase() {
    abstract fun guests(): GuestDao

    companion object {
        @Volatile private var instance: GuestBookDb? = null

        fun get(context: Context): GuestBookDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GuestBookDb::class.java, "guestbook.db")
                .build().also { instance = it }
        }
    }
}
