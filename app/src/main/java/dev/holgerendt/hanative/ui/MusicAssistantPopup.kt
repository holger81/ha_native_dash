package dev.holgerendt.hanative.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.data.IngressLoad
import dev.holgerendt.hanative.data.MusicAssistantLoadMode
import dev.holgerendt.hanative.data.MusicAssistantLoadTarget
import dev.holgerendt.hanative.model.PopupNode
import dev.holgerendt.hanative.ui.theme.ChipOnDark
import dev.holgerendt.hanative.ui.LoadingSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full Music Assistant panel in a Home Assistant WebView.
 * HA App panels load via Supervisor ingress; integration panels use frontend routes.
 */
@Composable
fun MusicAssistantPopup(popup: PopupNode, viewModel: HaViewModel) {
    val panelState by viewModel.musicAssistantPanelState.collectAsState()

    if (!panelState.resolved) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingSpinner()
        }
        return
    }

    val targets = panelState.targets
    if (targets.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Music Assistant panel not found.\n\n${panelState.debugInfo.joinToString("\n")}",
                color = ChipOnDark,
                fontSize = 14.sp,
            )
        }
        return
    }

    var targetIndex by remember(targets) { mutableIntStateOf(0) }
    var failedAttempts by remember(targets) { mutableStateOf(listOf<String>()) }
    val target = targets.getOrElse(targetIndex) { targets.last() }

    val debugInfo = buildList {
        addAll(panelState.debugInfo)
        if (targets.size > 1) {
            add("Load mode ${targetIndex + 1}/${targets.size}: ${target.label}")
        } else {
            add("Trying: ${target.label}")
        }
        failedAttempts.forEach { add("Failed: $it") }
    }

    val advanceOnFailure: (String) -> Unit = { message ->
        failedAttempts = failedAttempts + "${target.label}: $message"
        if (targetIndex + 1 < targets.size) {
            targetIndex++
        }
    }

    when (target.mode) {
        MusicAssistantLoadMode.INGRESS -> {
            val addonSlug = target.addonSlug
            if (addonSlug.isNullOrBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Music Assistant addon slug missing.", color = ChipOnDark, fontSize = 14.sp)
                }
                return
            }
            var ingressLoad by remember(targetIndex, addonSlug) { mutableStateOf<IngressLoad?>(null) }
            var ingressError by remember(targetIndex, addonSlug) { mutableStateOf<String?>(null) }

            LaunchedEffect(targetIndex, addonSlug) {
                ingressLoad = null
                ingressError = null
                val resolved = withContext(Dispatchers.IO) {
                    viewModel.resolveMusicAssistantIngress(addonSlug)
                }
                if (resolved == null) {
                    ingressError = "Could not create a verified ingress session for $addonSlug."
                } else {
                    ingressLoad = resolved
                }
            }

            when {
                ingressLoad != null -> {
                    val ingressPath = normalizeIngressPath(ingressLoad!!.ingressUrl)
                    val loadUrl = ingressLoadUrl(viewModel.savedUrl, ingressPath)
                    HaAuthenticatedWebView(
                        baseUrl = viewModel.savedUrl,
                        token = viewModel.savedToken,
                        path = ingressPath,
                        modifier = Modifier.fillMaxSize(),
                        useExternalAuth = false,
                        ingressSession = ingressLoad!!.session,
                        debugInfo = debugInfo + "Load URL: $loadUrl",
                        onLoadError = advanceOnFailure,
                        onContentBlank = advanceOnFailure,
                    )
                }
                ingressError != null -> {
                    LaunchedEffect(ingressError, targetIndex) {
                        failedAttempts = failedAttempts + "${target.label}: $ingressError"
                        if (targetIndex + 1 < targets.size) {
                            targetIndex++
                        }
                    }
                    if (targetIndex + 1 >= targets.size) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$ingressError\n\n${debugInfo.joinToString("\n")}",
                                color = ChipOnDark,
                                fontSize = 14.sp,
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LoadingSpinner()
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingSpinner()
                    }
                }
            }
        }
        MusicAssistantLoadMode.SPA_BOOTSTRAP -> {
            MusicAssistantSpaWebView(
                target = target,
                viewModel = viewModel,
                debugInfo = debugInfo,
                onLoadError = advanceOnFailure,
                onContentBlank = advanceOnFailure,
                bootstrapNavigatePath = target.path,
            )
        }
        MusicAssistantLoadMode.SPA_ROUTE -> {
            MusicAssistantSpaWebView(
                target = target,
                viewModel = viewModel,
                debugInfo = debugInfo,
                onLoadError = advanceOnFailure,
                onContentBlank = advanceOnFailure,
            )
        }
    }
}

@Composable
private fun MusicAssistantSpaWebView(
    target: MusicAssistantLoadTarget,
    viewModel: HaViewModel,
    debugInfo: List<String>,
    onLoadError: (String) -> Unit,
    onContentBlank: (String) -> Unit,
    bootstrapNavigatePath: String? = null,
) {
    val panelPath = target.path.ifBlank { "/" }
    val bootstrapPath = bootstrapNavigatePath?.trim()?.takeIf { it.isNotEmpty() }
    val initialPath = bootstrapPath?.let { "/" } ?: panelPath
    HaAuthenticatedWebView(
        baseUrl = viewModel.savedUrl,
        token = viewModel.savedToken,
        path = initialPath,
        modifier = Modifier.fillMaxSize(),
        bootstrapNavigatePath = bootstrapPath,
        debugInfo = debugInfo + "Load URL: ${haFrontendUrl(viewModel.savedUrl, initialPath)}",
        onLoadError = onLoadError,
        onContentBlank = onContentBlank,
    )
}
