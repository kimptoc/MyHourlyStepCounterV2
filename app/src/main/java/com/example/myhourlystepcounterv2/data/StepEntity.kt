package com.example.myhourlystepcounterv2.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hourly_steps")
data class StepEntity(
    @PrimaryKey
    val timestamp: Long, // Start of the hour in milliseconds
    val stepCount: Int,  // Number of steps taken during this hour
    val createdAt: Long = System.currentTimeMillis()
)
