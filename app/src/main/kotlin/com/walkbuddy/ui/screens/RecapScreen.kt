@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.RecapSlide
import com.walkbuddy.domain.SlideKind
import com.walkbuddy.share.ShareCard
import com.walkbuddy.share.ShareSpec
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.theme.BigNumberStyle
import com.walkbuddy.ui.theme.WbTheme
import kotlinx.coroutines.launch

// Deep, saturated pairs so white text keeps good contrast on every slide.
private fun palette(kind: SlideKind): Pair<Color, Color> = when (kind) {
    SlideKind.Total -> Color(0xFFB8325A) to Color(0xFF5A1A4A)
    SlideKind.BestDay -> Color(0xFFC2521F) to Color(0xFF7A2230)
    SlideKind.Together -> Color(0xFF0E7C7B) to Color(0xFF123B4A)
    SlideKind.BestHour -> Color(0xFF6B3FA0) to Color(0xFF2B1448)
    SlideKind.Flame -> Color(0xFFD9531E) to Color(0xFF8E2040)
    SlideKind.Closing -> Color(0xFFC93F67) to Color(0xFF3A1240)
}

/** The week as a story: full-screen slides you tap through or swipe, with segments on top like a story reel. */
@Composable
fun RecapScreen(vm: AppViewModel, onBack: () -> Unit) {
    val slides by vm.recap.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    RecapContent(
        slides = slides, onBack = onBack,
        onShare = { s, index -> ShareCard.share(ctx, ShareSpec("MY WEEK", s.big, s.title, (index + 1f) / slides.size, listOf(s.caption))) },
    )
}

@Composable
fun RecapContent(slides: List<RecapSlide>, onBack: () -> Unit, onShare: (RecapSlide, Int) -> Unit, initialPage: Int = 0) {
    val reduce = WbTheme.motion.reduceMotion
    val scope = rememberCoroutineScope()
    if (slides.isEmpty()) {
        EmptyState("Nothing to show yet", "Your recap appears once you have a few days of steps.")
        return
    }
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, slides.lastIndex), pageCount = { slides.size })
    fun goTo(i: Int) {
        val t = i.coerceIn(0, slides.lastIndex)
        scope.launch { if (reduce) pager.scrollToPage(t) else pager.animateScrollToPage(t) }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val slide = slides[page]
            val (a, b) = palette(slide.kind)
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a, b)))
                    .pointerInput(page) {
                        detectTapGestures { p -> if (p.x < size.width * 0.3f) goTo(pager.currentPage - 1) else goTo(pager.currentPage + 1) }
                    }
                    .semantics(mergeDescendants = true) { contentDescription = "${slide.title}. ${slide.big}. ${slide.caption}" },
                contentAlignment = Alignment.Center,
            ) {
                SlideBody(slide)
            }
        }

        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.indices.forEach { i ->
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = if (i <= pager.currentPage) 0.95f else 0.3f)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close recap", tint = Color.White) }
                IconButton(
                    onClick = { onShare(slides[pager.currentPage], pager.currentPage) },
                ) { Icon(Icons.Default.Share, contentDescription = "Share this slide", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun SlideBody(slide: RecapSlide) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(slide.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
        Text(
            slide.big, style = BigNumberStyle, fontSize = if (slide.big.length > 7) 56.sp else 96.sp,
            color = Color.White, textAlign = TextAlign.Center, maxLines = 1, fontWeight = FontWeight.Black,
        )
        Text(slide.caption, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.92f), textAlign = TextAlign.Center)
    }
}
