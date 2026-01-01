package com.example.myhourlystepcounterv2.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepRepository

class StepCounterViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        val database = StepDatabase.getDatabase(context)
        val repository = StepRepository(database.stepDao())
        return StepCounterViewModel(repository) as T
    }
}
