package com.base.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.base.editor.core.ProjectMeta
import com.base.editor.data.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Общее состояние вкладок «Дом / Проекты / Я». */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProjectRepository.get(app)

    @OptIn(ExperimentalCoroutinesApi::class)
    val projects: StateFlow<List<ProjectMeta>> = repo.revision
        .flatMapLatest { flow { emit(repo.list()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
}
