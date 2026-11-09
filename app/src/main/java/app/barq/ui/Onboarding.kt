package app.barq.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.barq.R
import kotlinx.coroutines.launch

private class Page(val icon: ImageVector, val title: Int, val body: Int)

private val pages = listOf(
    Page(BarqIcons.Bolt, R.string.ob1_title, R.string.ob1_body),
    Page(BarqIcons.Lock, R.string.ob2_title, R.string.ob2_body),
    Page(BarqIcons.Monitor, R.string.ob3_title, R.string.ob3_body),
    Page(BarqIcons.Phone, R.string.ob4_title, R.string.ob4_body),
)

/** Shown once, the first time the app opens (and never again after "Start" or "Skip"). */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val state = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = state.currentPage == pages.lastIndex

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.ob_skip), color = c.onSurfaceVariant) }
        }

        HorizontalPager(state, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(128.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(p.icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(p.body), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                val on = i == state.currentPage
                val color by animateColorAsState(if (on) c.primary else c.outline.copy(alpha = 0.5f), label = "dot")
                Box(Modifier.padding(horizontal = 4.dp).size(if (on) 10.dp else 8.dp).clip(CircleShape).background(color))
            }
        }

        BarqButton(
            stringResource(if (last) R.string.ob_start else R.string.ob_next),
            onClick = { if (last) onDone() else scope.launch { state.animateScrollToPage(state.currentPage + 1) } },
        )
    }
}
