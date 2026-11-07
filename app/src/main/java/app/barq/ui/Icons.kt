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
    val Settings: ImageVector by lazy { stroke("settings") { line("M12 9l3 3-3 3-3-3Z"); line("M12 2v3"); line("M12 19v3"); line("M2 12h3"); line("M19 12h3"); line("M5 5l2 2"); line("M17 17l2 2"); line("M5 19l2-2"); line("M17 7l2-2") } }
}
