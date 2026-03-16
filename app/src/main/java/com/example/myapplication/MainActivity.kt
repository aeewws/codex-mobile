package io.github.aeewws.codexmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.aeewws.codexmobile.runtime.ProjectRootOption
import io.github.aeewws.codexmobile.ui.app.CodexMobileApp
import io.github.aeewws.codexmobile.ui.app.CodexMobileViewModel
import io.github.aeewws.codexmobile.ui.theme.CodexMobileTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CodexMobileViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()

        setContent {
            CodexMobileTheme {
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
