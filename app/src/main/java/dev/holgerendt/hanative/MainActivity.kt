package dev.holgerendt.hanative

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.holgerendt.hanative.ui.HaApp
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.theme.HaNativeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: HaViewModel by viewModels { HaViewModel.factory(application) }
    private var askedStorage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as HaNativeApp).screenCapture.attach(this)
        maybeRequestStorage()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { applyScreenBrightness(it.screenAsleep) }
            }
        }
        setContent {
            HaNativeTheme {
                HaApp(viewModel = viewModel)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            viewModel.noteUserActivity()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        (application as HaNativeApp).screenCapture.attach(this)
        viewModel.retryRestoreIfNeeded()
        viewModel.onHostResumed()
    }

    override fun onDestroy() {
        (application as HaNativeApp).screenCapture.detach(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
            applyScreenBrightness(viewModel.ui.value.screenAsleep)
        }
    }

    private fun maybeRequestStorage() {
        if (askedStorage) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val needsRestore = viewModel.savedUrl.isBlank() || viewModel.savedToken.isBlank()
            if (needsRestore && !Environment.isExternalStorageManager()) {
                askedStorage = true
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                runCatching { startActivity(intent) }
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                askedStorage = true
                requestPermissions(needed.toTypedArray(), 1001)
            }
        }
    }

    private fun hideSystemUi() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applyScreenBrightness(asleep: Boolean) {
        if (!window.decorView.isAttachedToWindow) return
        runCatching {
            val lp = window.attributes
            val target = if (asleep) {
                0f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (lp.screenBrightness != target) {
                lp.screenBrightness = target
                window.attributes = lp
            }
        }
    }
}
