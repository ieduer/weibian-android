package net.bdfz.weibian.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.bdfz.weibian.ui.screens.ChallengeScreen
import net.bdfz.weibian.ui.screens.ChapterScreen
import net.bdfz.weibian.ui.screens.GaokaoDetailScreen
import net.bdfz.weibian.ui.screens.GaokaoListScreen
import net.bdfz.weibian.ui.screens.HomeScreen
import net.bdfz.weibian.ui.screens.MapScreen
import net.bdfz.weibian.ui.screens.ProfileScreen
import net.bdfz.weibian.ui.screens.RankingScreen

/**
 * 应用外壳。
 *
 * 用密封类做导航而不是引入 navigation-compose：本 App 的路由是有限且扁平的，
 * 一个 back stack 列表足够，也让返回行为完全可控（系统返回键与界面内返回一致）。
 */
sealed interface Route {
    data object Home : Route
    data object Map : Route
    data object Gaokao : Route
    data object Ranking : Route
    data object Profile : Route
    data class Chapter(val chapterId: Int) : Route
    data class GaokaoDetail(val gaokaoId: String) : Route
    data object Challenge : Route
}

/** 正文最大宽度：再宽中文行长就超出舒适阅读区间了。 */
private val READING_MAX_WIDTH = 760.dp

internal data class TabSpec(val route: Route, val label: String, val icon: ImageVector)

internal val primaryTabs = listOf(
    TabSpec(Route.Home, "今日", Icons.Filled.Home),
    TabSpec(Route.Map, "学程", Icons.Filled.Map),
    TabSpec(Route.Gaokao, "真题", Icons.Filled.School),
    TabSpec(Route.Ranking, "榜单", Icons.Filled.EmojiEvents),
    TabSpec(Route.Profile, "我", Icons.Filled.Person),
)

@Composable
fun WeibianApp(viewModel: WeibianViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkUpdate(force = false)
    }

    // 路由栈进 saveable，进程被回收后返回栈仍在。
    var stack by rememberSaveable(
        stateSaver = listSaver<List<Route>, String>(
            save = { routes -> routes.map(::encodeRoute) },
            restore = { saved -> saved.map(::decodeRoute) },
        ),
    ) { mutableStateOf(listOf<Route>(Route.Home)) }

    val current = stack.last()

    fun push(route: Route) {
        stack = stack + route
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun selectTab(route: Route) {
        // 切页签回到根，不在页签之间堆栈叠加。
        stack = listOf(route)
    }

    BackHandler(enabled = stack.size > 1) {
        if (current is Route.Challenge) viewModel.clearChallenge()
        pop()
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current.isPrimaryDestination()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    primaryTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current::class == tab.route::class,
                            onClick = { selectTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 大屏上不把内容拉满：一行 1280dp 宽的古文没法读。
        // 用 BoxWithConstraints 显式算出正文宽度再定宽——
        // 直接写 fillMaxSize().widthIn(max=…) 是没用的，fillMaxSize
        // 会把宽度的最小约束也顶到父容器宽度，widthIn 的上限根本不起作用。
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@BoxWithConstraints
            }

            // 手机上 maxWidth 远小于 760dp，取 min 后布局与原来完全一致。
            val contentWidth = minOf(maxWidth, READING_MAX_WIDTH)
            Box(Modifier.width(contentWidth).fillMaxHeight()) {
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "route",
            ) { route ->
                when (route) {
                    is Route.Home -> HomeScreen(
                        state = state,
                        onOpenChapter = { push(Route.Chapter(it)) },
                        onStartDaily = {
                            viewModel.startDailyChallenge()
                            push(Route.Challenge)
                        },
                        onReviewMistakes = {
                            viewModel.startMistakeReview()
                            push(Route.Challenge)
                        },
                        onOpenMap = { selectTab(Route.Map) },
                    )

                    is Route.Map -> MapScreen(
                        state = state,
                        onOpenChapter = { push(Route.Chapter(it)) },
                    )

                    is Route.Gaokao -> GaokaoListScreen(
                        state = state,
                        onOpen = { push(Route.GaokaoDetail(it)) },
                    )

                    is Route.Ranking -> RankingScreen(
                        state = state,
                        viewModel = viewModel,
                    )

                    is Route.Profile -> ProfileScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenChapter = { push(Route.Chapter(it)) },
                        onReviewMistakes = {
                            viewModel.startMistakeReview()
                            push(Route.Challenge)
                        },
                    )

                    is Route.Chapter -> ChapterScreen(
                        chapterId = route.chapterId,
                        state = state,
                        viewModel = viewModel,
                        onBack = { pop() },
                        onPractice = {
                            viewModel.startChapterChallenge(route.chapterId)
                            push(Route.Challenge)
                        },
                        onOpenGaokao = { push(Route.GaokaoDetail(it)) },
                        onOpenChapter = { push(Route.Chapter(it)) },
                    )

                    is Route.GaokaoDetail -> GaokaoDetailScreen(
                        gaokaoId = route.gaokaoId,
                        state = state,
                        viewModel = viewModel,
                        onBack = { pop() },
                        onOpenChapter = { push(Route.Chapter(it)) },
                    )

                    is Route.Challenge -> ChallengeScreen(
                        challenge = challenge,
                        state = state,
                        onChoose = viewModel::chooseOption,
                        onNext = viewModel::nextTask,
                        onFinish = {
                            viewModel.clearChallenge()
                            pop()
                        },
                        onOpenChapter = { push(Route.Chapter(it)) },
                    )
                }
            }
            }
        }
    }
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

// rememberSaveable 需要能把路由存进 Bundle，这里用一行字符串编码。
internal fun Route.isPrimaryDestination(): Boolean =
    primaryTabs.any { tab -> this::class == tab.route::class }

internal fun encodeRoute(route: Route): String = when (route) {
    is Route.Home -> "home"
    is Route.Map -> "map"
    is Route.Gaokao -> "gaokao"
    is Route.Ranking -> "ranking"
    is Route.Profile -> "profile"
    is Route.Challenge -> "challenge"
    is Route.Chapter -> "chapter:${route.chapterId}"
    is Route.GaokaoDetail -> "gk:${route.gaokaoId}"
}

internal fun decodeRoute(value: String): Route = when {
    value == "map" -> Route.Map
    value == "gaokao" -> Route.Gaokao
    value == "ranking" -> Route.Ranking
    value == "profile" -> Route.Profile
    value == "challenge" -> Route.Challenge
    value.startsWith("chapter:") -> Route.Chapter(value.removePrefix("chapter:").toIntOrNull() ?: 1)
    value.startsWith("gk:") -> Route.GaokaoDetail(value.removePrefix("gk:"))
    else -> Route.Home
}
