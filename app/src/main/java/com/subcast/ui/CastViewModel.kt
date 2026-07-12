package com.subcast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subcast.cast.CastEngine
import com.subcast.cast.CastUiState
import com.subcast.data.SubCastDatabase
import kotlinx.coroutines.flow.StateFlow

class CastViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = SubCastDatabase.get(app).resumeDao()
    val engine = CastEngine(app.applicationContext, dao, viewModelScope)

    val state: StateFlow<CastUiState> = engine.state

    init {
        engine.startServices()
    }

    override fun onCleared() {
        engine.destroy()
        super.onCleared()
    }
}
