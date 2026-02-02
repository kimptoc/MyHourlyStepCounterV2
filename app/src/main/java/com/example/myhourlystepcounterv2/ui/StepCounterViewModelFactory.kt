package com.example.myhourlystepcounterv2.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepRepository

/**
 * Factory for creating StepCounterViewModel with dependency injection.
 * Should be instantiated with context.applicationContext (not Activity context)
 * to avoid context leaks in the database and repository.
 */
class StepCounterViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        val database = StepDatabase.getDatabase(context)
        val repository = StepRepository(database.stepDao())
        return StepCounterViewModel(repository) as T
    }
}
