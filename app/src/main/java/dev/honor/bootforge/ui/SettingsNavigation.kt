package dev.honor.bootforge.ui

import android.util.Log
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

internal enum class SettingsDetailPage(val title: String) {
    SUPPORT("支持"), LICENSE("许可证")
}

/** One motion source drives both full windows, including their chrome. */
@Stable
internal class SettingsNavigationState(
    private val route: MutableState<Boolean>,
    private val detailRoute: MutableState<SettingsDetailPage>,
    private val scope: CoroutineScope,
) {
    private var animationJob: Job? = null
    private var revision = 0L
    private var predictiveStartX = 0f

    var offsetX by mutableFloatStateOf(if (route.value) 0f else 1f)
        private set

    // Both settings detail routes keep the same input ownership and motion.
    val detailVisible: Boolean get() = route.value
    val detailPage: SettingsDetailPage get() = detailRoute.value
    val progress: Float get() = offsetX.coerceIn(0f, 1f)

    private fun interrupt(): Long {
        val token = ++revision
        animationJob?.cancel()
        animationJob = null
        return token
    }

    fun openSupport() = openDetail(SettingsDetailPage.SUPPORT)

    fun openLicense() = openDetail(SettingsDetailPage.LICENSE)

    private fun openDetail(page: SettingsDetailPage) {
        val wasVisible = detailVisible
        val token = interrupt()
        detailRoute.value = page
        route.value = true
        if (!wasVisible) {
            offsetX = 1f
        }
        settle(token, 0f, false)
    }

    fun closeSupport() = closeThen {}

    fun closeThen(action: () -> Unit) {
        if (!detailVisible) {
            action()
            return
        }
        val token = interrupt()
        settle(token, 1f, true, after = action)
    }

    internal fun cancelPredictive(token: Long) {
        if (token != revision || !detailVisible) return
        settle(interrupt(), 0f, false)
    }

    internal fun beginPredictive(): Long? {
        if (!detailVisible) return null
        val token = interrupt()
        predictiveStartX = offsetX
        return token
    }

    internal fun predictive(token: Long, backProgress: Float) {
        if (token != revision || !detailVisible || !backProgress.isFinite()) return
        // Both system edges control the same rightward window return.
        val p = backProgress.coerceIn(0f, 1f)
        // A preview stays visible while held; only a completed Back flies fully offscreen.
        offsetX = (predictiveStartX + (1f - predictiveStartX) * .38f * p).coerceIn(0f, 1f)
    }

    internal fun completePredictive(token: Long) {
        if (token != revision || !detailVisible) return
        // An ordinary Back can complete an empty progress flow.
        settle(interrupt(), 1f, true)
    }

    private fun settle(
        token: Long,
        target: Float,
        close: Boolean,
        after: () -> Unit = {},
    ) {
        val fromX = offsetX
        val distance = target - fromX
        if (abs(distance) < .0005f) {
            if (revision == token) {
                offsetX = target
                if (close) {
                    route.value = false
                    after()
                }
            }
            return
        }
        animationJob = scope.launch {
            try {
                animate(0f, 1f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = 480f, visibilityThreshold = .0005f)) { value, _ ->
                    if (revision == token) {
                        offsetX = (fromX + distance * value).coerceIn(0f, 1f)
                    }
                }
                if (revision == token) {
                    offsetX = target
                    if (close) {
                        route.value = false
                        after()
                    }
                }
            } finally {
                if (revision == token) animationJob = null
            }
        }
    }
}

@Composable
internal fun rememberSettingsNavigation(): SettingsNavigationState {
    val route = rememberSaveable { mutableStateOf(false) }
    val detailRoute = rememberSaveable { mutableStateOf(SettingsDetailPage.SUPPORT) }
    val scope = rememberCoroutineScope()
    return remember(route, detailRoute, scope) { SettingsNavigationState(route, detailRoute, scope) }
}

/** Applied to the complete background page, rather than only its scrolling body. */
internal fun Modifier.supportUnderlay(navigation: SettingsNavigationState, enabled: Boolean): Modifier = graphicsLayer {
    val engaged = enabled && navigation.detailVisible
    val p = navigation.progress
    scaleX = if (engaged) .94f + .06f * p else 1f
    scaleY = scaleX
    translationX = if (engaged) -size.width * .12f * (1f - p) else 0f
    shape = RoundedCornerShape((if (engaged) 32f * (1f - p) else 0f).dp)
    clip = engaged
}

/** The host blocks lower hit targets; only system Back controls the window motion. */
@Composable
internal fun SupportWindow(
    navigation: SettingsNavigationState,
    active: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled = active && navigation.detailVisible
    PredictiveBackHandler(enabled = enabled) { events ->
        val token = navigation.beginPredictive()
        var eventCount = 0
        var lastProgress = 0f
        Log.i("HnublBack", "predictive start")
        try {
            events.collect { event ->
                eventCount++
                lastProgress = event.progress
                if (eventCount == 1) Log.i("HnublBack", "first progress=${event.progress} edge=${event.swipeEdge}")
                if (token != null) navigation.predictive(token, event.progress)
            }
            Log.i("HnublBack", "complete events=$eventCount lastProgress=$lastProgress")
            if (token != null) navigation.completePredictive(token)
        } catch (cancelled: CancellationException) {
            Log.i("HnublBack", "cancel events=$eventCount lastProgress=$lastProgress")
            // Launch rebound through the independent UI scope, not this cancelled job.
            if (token != null) navigation.cancelPredictive(token)
            throw cancelled
        }
    }
    if (!enabled) return
    Box(Modifier.fillMaxSize()
        .pointerInput(navigation) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Final)
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = navigation.progress
            translationX = navigation.offsetX * size.width
            scaleX = 1f - .16f * p
            scaleY = scaleX
            shape = RoundedCornerShape((32f * p).dp)
            clip = p > 0f
            shadowElevation = 12.dp.toPx() * p
        }.background(MiuixTheme.colorScheme.background), content = content)
    }
}
