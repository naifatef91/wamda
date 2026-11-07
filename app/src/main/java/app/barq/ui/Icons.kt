package app.barq.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Stroke icons from the design canvas, 24dp grid, no icon library needed. */
object BarqIcons {
    private fun stroke(name: String, width: Float = 2f, build: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private fun ImageVector.Builder.line(d: String, w: Float = 2f) = path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = w,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathFillType = PathFillType.NonZero,
    ) { addPath(d) }

    private fun ImageVector.Builder.fill(d: String) = path(fill = SolidColor(Color.Black)) { addPath(d) }

    private fun androidx.compose.ui.graphics.vector.PathBuilder.addPath(d: String) {
        // Tiny SVG path parser for M/L/H/V/Z/C/A-free paths used below (absolute and relative m,l,h,v).
        val tokens = Regex("[a-zA-Z]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var cx = 0f; var cy = 0f
        fun num() = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z' || cmd == 'z') { close(); continue } }
            when (cmd) {
                'M' -> { cx = num(); cy = num(); moveTo(cx, cy); cmd = 'L' }
                'm' -> { cx += num(); cy += num(); moveTo(cx, cy); cmd = 'l' }
                'L' -> { cx = num(); cy = num(); lineTo(cx, cy) }
                'l' -> { cx += num(); cy += num(); lineTo(cx, cy) }
                'H' -> { cx = num(); lineTo(cx, cy) }
                'h' -> { cx += num(); lineTo(cx, cy) }
                'V' -> { cy = num(); lineTo(cx, cy) }
                'v' -> { cy += num(); lineTo(cx, cy) }
                'C' -> { val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num(); cx = num(); cy = num(); curveTo(x1, y1, x2, y2, cx, cy) }
                'c' -> { val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num(); val dx = num(); val dy = num(); curveToRelative(x1, y1, x2, y2, dx, dy); cx += dx; cy += dy }
                'S' -> { val x2 = num(); val y2 = num(); cx = num(); cy = num(); reflectiveCurveTo(x2, y2, cx, cy) }
                's' -> { val x2 = num(); val y2 = num(); val dx = num(); val dy = num(); reflectiveCurveToRelative(x2, y2, dx, dy); cx += dx; cy += dy }
                else -> i++
            }
        }
    }

    val Bolt: ImageVector by lazy { stroke("bolt") { fill("M13 2 5 14h6l-1 8 8-12h-6l1-8Z") } }
    val ArrowUp: ImageVector by lazy { stroke("up") { line("M12 19V5"); line("M5 12l7-7 7 7") } }
    val ArrowDown: ImageVector by lazy { stroke("down") { line("M12 5v14"); line("M19 12l-7 7-7-7") } }
    val Back: ImageVector by lazy { stroke("back") { line("M9 6l6 6-6 6") } }
    val ArrowUpRight: ImageVector by lazy { stroke("upright") { line("M7 17L17 7"); line("M9 7h8v8") } }
    val ArrowDownLeft: ImageVector by lazy { stroke("downleft") { line("M17 7L7 17"); line("M15 17H7V9") } }
    val Next: ImageVector by lazy { stroke("next") { line("M15 6l-6 6 6 6") } }
    val Check: ImageVector by lazy { stroke("check") { line("M5 12l5 5L20 7", 2.6f) } }
    val Lock: ImageVector by lazy { stroke("lock") { line("M4 13h16v8H4Z", 2.4f); line("M8 13V9l1-3 3-1 3 1 1 3v4", 2.4f) } }
    val File: ImageVector by lazy { stroke("file") { line("M14 2H6l-2 2v16l2 2h12l2-2V8Z"); line("M14 2v6h6") } }
    val Image: ImageVector by lazy { stroke("image") { line("M3 3h18v18H3Z"); line("M21 15l-5-5L5 21") } }
    val Video: ImageVector by lazy { stroke("video") { line("M3 5h18v14H3Z"); line("M10 9l5 3-5 3Z") } }
    val Phone: ImageVector by lazy { stroke("phone") { line("M6 2h12v20H6Z"); line("M11 18h2") } }
    val Qr: ImageVector by lazy {
        stroke("qr") {
            line("M3 7V5l2-2h2"); line("M17 3h2l2 2v2"); line("M21 17v2l-2 2h-2"); line("M7 21H5l-2-2v-2")
            line("M7 7h4v4H7Z"); line("M13 7h4v4h-4Z"); line("M7 13h4v4H7Z"); line("M13 13h4v4")
        }
    }
    val Wifi: ImageVector by lazy { stroke("wifi") { line("M5 12.5l7-4 7 4", 2.2f); line("M8.5 16l3.5-2 3.5 2", 2.2f); line("M12 19.5h0.01", 2.2f) } }
    val Warning: ImageVector by lazy { stroke("warn") { line("M12 9v4"); line("M12 17h0.01"); line("M12 3 2 21h20Z") } }
    /** Classic gear (Material "settings" outline shape, filled with a hollow centre via even-odd). */
    val Settings: ImageVector by lazy {
        ImageVector.Builder("settings", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                addPath(
                    "M19.14 12.94c0.04-0.3 0.06-0.61 0.06-0.94 0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14 0.23-0.41 0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39 0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4 2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24 0-0.43 0.17-0.47 0.41L9.25 5.35C8.66 5.59 8.12 5.92 7.63 6.29L5.24 5.33c-0.22-0.08-0.47 0-0.59 0.22L2.74 8.87C2.62 9.08 2.66 9.34 2.86 9.48l2.03 1.58C4.84 11.36 4.8 11.69 4.8 12s0.02 0.64 0.07 0.94l-2.03 1.58c-0.18 0.14-0.23 0.41-0.12 0.61l1.92 3.32c0.12 0.22 0.37 0.29 0.59 0.22l2.39-0.96c0.5 0.38 1.03 0.7 1.62 0.94l0.36 2.54c0.05 0.24 0.24 0.41 0.48 0.41h3.84c0.24 0 0.44-0.17 0.47-0.41l0.36-2.54c0.59-0.24 1.13-0.56 1.62-0.94l2.39 0.96c0.22 0.08 0.47 0 0.59-0.22l1.92-3.32c0.12-0.22 0.07-0.47-0.12-0.61L19.14 12.94z" +
                        "M12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
                )
            }
        }.build()
    }
}
