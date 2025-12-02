package com.example.booxreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.OnConflictStrategy

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE fileUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastLocatorJson = :locatorJson, lastOpenedAt = :time WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: String, locatorJson: String, time: Long)
}