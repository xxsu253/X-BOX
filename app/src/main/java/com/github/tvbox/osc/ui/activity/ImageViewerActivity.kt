package com.github.tvbox.osc.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import kotlinx.coroutines.launch

/**
 * pics:// 看图页 v2。
 *
 * v1 是横向 HorizontalPager + 图片上的双指手势：双指手势会吃掉所有拖动事件，
 * 导致左右滑永远到不了 Pager，图片根本切不动——这是 v1“不能切换”的根因。
 * v2 改成竖向翻页（VerticalPager，一页一张，上下滑切换），图片上不再挂吃拖动
 * 的手势：缩放只走双击和底部按钮，放大后才由图片接管单指拖动平移（此时翻页
 * 自动停用，缩回 1 倍即恢复翻页）。
 *
 * 顶部：左返回箭头 + 标题 + 当前/总数；底部：滑块跳页 + 上一张/下一张 +
 * 缩放按钮。图片仍用壳里现成的 Coil 加载（@Referer/@Cookie 等后缀头、
 * 本地代理图、磁盘缓存都和封面走同一套）。
 */
class ImageViewerActivity : ComponentActivity() {

    companion object {
        const val SCHEME = "pics://"
        private const val EXTRA_URLS = "pics_urls"
        private const val EXTRA_TITLE = "pics_title"
        private const val EXTRA_INDEX = "pics_index"

        /** 拆 pics:// 地址；不是 pics:// 返回 null，pics:// 但无有效图返回空列表。 */
        fun parse(url: String?): List<String>? {
            if (url == null || !url.startsWith(SCHEME)) return null
            return url.substring(SCHEME.length).split("&&")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        fun start(context: Context, title: String, urls: List<String>, index: Int = 0) {
            val intent = Intent(context, ImageViewerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_INDEX, index.coerceIn(0, (urls.size - 1).coerceAtLeast(0)))
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 黑底看图页，状态栏图标用浅色，保证在黑底上可见
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
            .coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        if (urls.isEmpty()) {
            finish()
            return
        }
        setContent {
            ImageViewerScreen(
                title = title,
                urls = urls,
                startIndex = index,
                onClose = { finish() },
            )
        }
    }
}

@Composable
private fun ImageViewerScreen(
    title: String,
    urls: List<String>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { urls.size },
    )
    val scope = rememberCoroutineScope()
    // 缩放状态提升到页外：换页即重置；放大时翻页停用、图片接管拖动平移
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var sliderPos by remember { mutableStateOf(startIndex.toFloat()) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
        sliderPos = pagerState.currentPage.toFloat()
    }
    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = Color.White,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${pagerState.currentPage + 1}/${urls.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        VerticalPager(
            state = pagerState,
            userScrollEnabled = scale <= 1f,
            modifier = Modifier.weight(1f),
        ) { page ->
            ZoomableImage(
                url = urls[page],
                scale = scale,
                offset = offset,
                onOffsetChange = { offset = it },
                onDoubleTap = {
                    if (scale > 1f) {
                        resetZoom()
                    } else {
                        scale = 2f
                    }
                },
            )
        }
        // 底部：滑块跳页 + 上一张/下一张 + 缩放
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (urls.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Slider(
                        value = sliderPos,
                        onValueChange = { sliderPos = it },
                        onValueChangeFinished = {
                            scope.launch {
                                pagerState.scrollToPage(sliderPos.toInt())
                            }
                        },
                        valueRange = 0f..(urls.size - 1).toFloat(),
                        steps = (urls.size - 2).coerceAtLeast(0),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${urls.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    enabled = pagerState.currentPage > 0,
                ) {
                    Text(
                        text = stringResource(R.string.pics_viewer_prev),
                        color = Color.White,
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    enabled = pagerState.currentPage < urls.size - 1,
                ) {
                    Text(
                        text = stringResource(R.string.pics_viewer_next),
                        color = Color.White,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.pics_viewer_zoom),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                TextButton(
                    onClick = {
                        scale = (scale - 0.5f).coerceIn(1f, 5f)
                        if (scale <= 1f) offset = Offset.Zero
                    },
                ) {
                    Text(text = "－", color = Color.White)
                }
                Text(
                    text = "${(scale * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                TextButton(
                    onClick = {
                        scale = (scale + 0.5f).coerceIn(1f, 5f)
                    },
                ) {
                    Text(text = "＋", color = Color.White)
                }
                TextButton(onClick = { resetZoom() }) {
                    Text(
                        text = stringResource(R.string.pics_viewer_reset),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    scale: Float,
    offset: Offset,
    onOffsetChange: (Offset) -> Unit,
    onDoubleTap: () -> Unit,
) {
    var failed by remember(url) { mutableStateOf(false) }
    // 手势块以 url 为 key，不随 scale/offset 变化重启；回调里的 scale/offset 必须
    // 读最新值，否则双击第二次、拖动累计都会用到旧值。
    val latestOffset by rememberUpdatedState(offset)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(
                text = stringResource(R.string.pics_viewer_load_failed),
                color = Color.White,
            )
        } else {
            // 注意：这里不能挂 detectTransformGestures，它会吃掉拖动导致翻页失效。
            // 双击不吃拖动；单指拖动平移只在放大后挂接，此时翻页已停用，不冲突。
            val dragModifier = if (scale > 1f) {
                Modifier.pointerInput(url) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onOffsetChange(latestOffset + dragAmount)
                    }
                }
            } else {
                Modifier
            }
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(url) {
                        detectTapGestures(onDoubleTap = { latestDoubleTap() })
                    }
                    .then(dragModifier)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
}
