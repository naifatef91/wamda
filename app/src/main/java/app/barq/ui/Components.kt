package app.barq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.barq.ui.theme.extras

enum class ButtonKind { Primary, Tonal, Outlined, Danger }

@Composable
fun BarqButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    height: Int = 56,
    leading: ImageVector? = null,
) {
    val c = MaterialTheme.colorScheme
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.primary, c.onPrimary, Color.Transparent)
        ButtonKind.Tonal -> Triple(c.primaryContainer, c.onPrimaryContainer, Color.Transparent)
        ButtonKind.Outlined -> Triple(c.surface, c.onSurface, c.outline)
        ButtonKind.Danger -> Triple(c.surface, c.error, c.outline)
    }
    val shape = RoundedCornerShape(if (height >= 56) 16.dp else 14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .border(1.5.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = fg, style = if (height >= 56) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
    }
}

enum class ChipTone { Ok, Warn, Accent, Neutral }

@Composable
fun BarqChip(text: String, tone: ChipTone, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val (bg, fg) = when (tone) {
        ChipTone.Ok -> x.okSoft to x.ok
        ChipTone.Warn -> x.warnSoft to x.warn
        ChipTone.Accent -> c.primaryContainer to c.onPrimaryContainer
        ChipTone.Neutral -> c.background to c.onSurfaceVariant
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (tone == ChipTone.Neutral) Modifier.border(1.dp, c.outline, CircleShape) else Modifier)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BarqCard(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(tint ?: c.surface)
            .border(1.dp, c.outline, MaterialTheme.shapes.medium),
    ) { content() }
}

@Composable
fun IconBadge(icon: ImageVector, bg: Color, fg: Color, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size((size / 2).dp)) }
}

@Composable
fun ThickProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.primaryContainer,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.primaryContainer,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Speed history drawn as an area sparkline, matching the Transfer artboard. */
@Composable
fun Sparkline(samples: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        if (samples.size < 2) return@Canvas
        val max = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val stepX = size.width / (samples.size - 1)
        val pts = samples.mapIndexed { i, v -> Offset(i * stepX, size.height - (v / max) * (size.height - 8f) - 4f) }
        val line = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, size.height)
            lineTo(pts.first().x, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, radius = 4.dp.toPx(), center = pts.last())
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FileRow(name: String, trailing: String, fraction: Float, icon: ImageVector, done: Boolean, skipped: Boolean) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    val badgeBg = when { skipped -> c.background; done -> x.okSoft; else -> c.primaryContainer }
    val badgeFg = when { skipped -> c.onSurfaceVariant; done -> x.ok; else -> c.primary }
    val barColor = when { skipped -> c.outline; done -> x.ok; else -> c.primary }
    BarqCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBadge(icon, badgeBg, badgeFg, size = 34)
                Text(
                    name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                Text(trailing, style = MaterialTheme.typography.bodySmall, color = if (skipped) x.warn else c.onSurfaceVariant)
            }
            ThinProgress(if (skipped || done) 1f else fraction, color = barColor)
        }
    }
}
