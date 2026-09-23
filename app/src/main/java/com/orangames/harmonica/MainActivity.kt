package com.orangames.harmonica

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.orangames.harmonica.ui.EditorModel
import com.orangames.harmonica.ui.EditorModelFactory
import com.orangames.harmonica.ui.EditorScreen
import com.orangames.harmonica.ui.HarmonicaTheme
import com.orangames.harmonica.ui.HomeScreen
import com.orangames.harmonica.ui.LibraryModel
import com.orangames.harmonica.ui.PlayerScreen
import com.orangames.harmonica.ui.RecordScreen
import java.io.File

object HarmonicaAppHolder {
    fun sourceOf(context: Context, id: String): File {
        val app = context.applicationContext as HarmonicaApp
        return app.store.sourceFile(id)
    }
}

class MainActivity : ComponentActivity() {
    private val libraryModel: LibraryModel by viewModels {
        val store = (application as HarmonicaApp).store
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryModel(store) as T
        }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT <= 28) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                storagePermission.launch(permission)
            }
        }
        val store = (application as HarmonicaApp).store
        setContent {
            HarmonicaTheme {
                AppNav(store = store, library = libraryModel)
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Record : Screen
    data class Play(val id: String) : Screen
    data class Edit(val id: String) : Screen
}

@Composable
private fun AppNav(store: com.orangames.harmonica.data.TakeStore, library: LibraryModel) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun push(screen: Screen) { stack.add(screen) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    when (val screen = stack.last()) {
        Screen.Home -> HomeScreen(
            model = library,
            store = store,
            onRecord = { push(Screen.Record) },
            onPlay = { push(Screen.Play(it)) },
            onEdit = { push(Screen.Edit(it)) },
        )
        Screen.Record -> RecordScreen(store = store, onBack = { pop() }, onDone = { pop() })
        is Screen.Play -> PlayerScreen(
            store = store,
            id = screen.id,
            onBack = { pop() },
            onEdit = { push(Screen.Edit(screen.id)) },
        )
        is Screen.Edit -> {
            val model: EditorModel = androidx.lifecycle.viewmodel.compose.viewModel(
                key = screen.id,
                factory = EditorModelFactory(store, screen.id),
            )
            EditorScreen(model = model, onBack = { pop() })
        }
    }
}
