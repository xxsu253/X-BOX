package com.github.tvbox.osc.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R

/**
 * pics:// 看图页。
 *
 * 有些源的图片帖子（比如写真、漫画）不走视频播放：源的 playerContent 返回
 * pics://图1&&图2&&...，播放器以前不认这个 scheme，点开就什么都不显示。
 * 现在 PlaybackStarter 在播放分发前截住 pics://，改打开本页：左右滑翻图，
 * 双击/双指缩放；图片用壳里现成的 Coil 加载（@Referer/@Cookie 等后缀头、
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
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { zoomed = false }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            )
            TextButton(onClick = onClose) {
                Text(
                    text = stringResource(R.string.common_back),
                    color = Color.White,
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            // 放大时禁止左右滑，避免缩放拖动误翻页；缩回 1 倍后恢复滑动
            userScrollEnabled = !zoomed,
            modifier = Modifier.weight(1f),
        ) { page ->
            ZoomableImage(
                url = urls[page],
                onZoomChanged = { zoomed = it },
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var failed by remember(url) { mutableStateOf(false) }
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
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(url) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2f
                                }
                                onZoomChanged(scale > 1f)
                            },
                        )
                    }
                    .pointerInput(url) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next <= 1f) Offset.Zero else offset + pan
                            onZoomChanged(next > 1f)
                        }
                    }
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
