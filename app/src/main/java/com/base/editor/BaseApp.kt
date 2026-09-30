package com.base.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.base.editor.core.PickedMedia
import com.base.editor.data.ProjectRepository
import com.base.editor.ui.editor.EditorScreen
import com.base.editor.ui.picker.MediaPickerScreen
import com.base.editor.ui.theme.BaseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val MAIN = "main"
    const val PICKER_NEW = "picker/new/{tab}"
    const val PICKER_ADD = "picker/add"
    const val EDITOR = "editor/{projectId}"
    fun pickerNew(photo: Boolean) = "picker/new/${if (photo) "photo" else "video"}"
    fun editor(id: String) = "editor/$id"
}

@Composable
fun BaseApp() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var splash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(900); splash = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NavHost(nav, startDestination = Routes.MAIN) {
            composable(Routes.MAIN) {
                BaseTheme(dark = false) {
                    MainScreen(
                        onNewVideo = { nav.navigate(Routes.pickerNew(photo = false)) },
                        onEditPhoto = { nav.navigate(Routes.pickerNew(photo = true)) },
                        onOpenProject = { nav.navigate(Routes.editor(it)) },
                    )
                }
            }
            composable(Routes.PICKER_NEW, arguments = listOf(navArgument("tab") { type = NavType.StringType })) { e ->
                BaseTheme(dark = true) {
                    MediaPickerScreen(
                        startOnPhotos = e.arguments?.getString("tab") == "photo",
                        onClose = { nav.popBackStack() },
                        onConfirm = { items: List<PickedMedia> ->
                            scope.launch {
                                val id = ProjectRepository.get(ctx).create(items)
                                nav.navigate(Routes.editor(id)) { popUpTo(Routes.MAIN) }
                            }
                        },
                    )
                }
            }
            // Добавление медиа в открытый проект: результат уходит в SavedStateHandle редактора
            composable(Routes.PICKER_ADD) {
                BaseTheme(dark = true) {
                    MediaPickerScreen(
                        startOnPhotos = false,
                        onClose = { nav.popBackStack() },
                        onConfirm = { items ->
                            nav.previousBackStackEntry?.savedStateHandle?.set("added", ArrayList(items.map { it.encode() }))
                            nav.popBackStack()
                        },
                    )
                }
            }
            composable(Routes.EDITOR, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) {
                BaseTheme(dark = true) {
                    EditorScreen(onClose = { nav.popBackStack() }, onAddMedia = { nav.navigate(Routes.PICKER_ADD) })
                }
            }
        }
        AnimatedVisibility(visible = splash, exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.logo_base_white), "BASE", Modifier.width(180.dp))
            }
        }
    }
}
