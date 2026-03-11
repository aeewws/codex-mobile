package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.myapplication.runtime.ProjectRootOption
import com.example.myapplication.ui.app.CodexMobileApp
import com.example.myapplication.ui.app.CodexMobileViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CodexMobileViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                CodexMobileApp(
                    viewModel = viewModel,
                    onOpenTermux = { viewModel.openTermux(this@MainActivity) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResume()
    }

    override fun onPause() {
        viewModel.onAppPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            viewModel.onUiClosed()
        }
        super.onDestroy()
    }

    suspend fun showProjectRootPicker(options: List<ProjectRootOption>): String? {
        return options.firstOrNull()?.path
    }

    fun dispatchToJs(id: String, ok: Boolean, payloadJson: String) {
        // The legacy WebView bridge is kept only for build compatibility.
    }
}
