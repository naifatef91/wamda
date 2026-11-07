package app.barq.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import android.view.ViewGroup
import app.barq.R
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * QR scanner with a clearly framed viewfinder.
 *
 * Reuses the zxing [CaptureActivity] (camera permission flow, decoding, result
 * delivery through `ScanContract`) and only swaps the content layout for one
 * with a 260dp framing rect, a dim mask and the [ScanFrameView] overlay.
 *
 * Launch it with `ScanOptions().setCaptureActivity(ScanActivity::class.java)`.
 */
class ScanActivity : CaptureActivity() {

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan)
        val close = findViewById<View>(R.id.scan_close)
        close.setOnClickListener { finish() }
        // The capture theme hides the system bars on most devices, but keep the
        // close button clear of the status bar / cutout when they are visible.
        ViewCompat.setOnApplyWindowInsetsListener(close) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top + dp(v.context, 12f).toInt() }
            insets
        }
        return findViewById(R.id.zxing_barcode_scanner)
    }
    // Camera permission handling, orientation and decoding stay with CaptureActivity.
}

private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

/**
 * Transparent overlay drawn above the camera preview: a centred rounded square
 * the size of the zxing framing rect, four bold white corner brackets, a hairline
 * border, and a one-line instruction below the square.
 */
class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val frameSize = dp(context, 260f)
    private val cornerRadius = dp(context, 18f)
    private val bracketLen = dp(context, 28f)
    private val bracketStroke = dp(context, 6f)
    private val textGap = dp(context, 24f)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 1f)
        color = Color.argb(140, 255, 255, 255)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = bracketStroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(context, 15f)
        setShadowLayer(dp(context, 6f), 0f, dp(context, 1f), Color.argb(170, 0, 0, 0))
    }

    private val hint: String = context.getString(R.string.scan_hint)
    private val rect = RectF()
    private val path = Path()
    private var textLayout: StaticLayout? = null

    init {
        // Let touches reach the preview underneath (tap-to-focus etc.).
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(frameSize, w * 0.86f, h * 0.6f)
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        rect.set(left, top, left + size, top + size)

        val textWidth = (w - 2 * dp(context, 32f)).toInt().coerceAtLeast(1)
        textLayout = StaticLayout.Builder.obtain(hint, 0, hint.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) return

        // Subtle 1dp rounded border.
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Four corner brackets, each following the rounded corner.
        val r = cornerRadius
        val l = bracketLen
        val inset = bracketStroke / 2f
        val b = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)

        path.reset()
        // top-left
        path.moveTo(b.left, b.top + l)
        path.lineTo(b.left, b.top + r)
        path.arcTo(b.left, b.top, b.left + 2 * r, b.top + 2 * r, 180f, 90f, false)
        path.lineTo(b.left + l, b.top)
        // top-right
        path.moveTo(b.right - l, b.top)
        path.lineTo(b.right - r, b.top)
        path.arcTo(b.right - 2 * r, b.top, b.right, b.top + 2 * r, 270f, 90f, false)
        path.lineTo(b.right, b.top + l)
        // bottom-right
        path.moveTo(b.right, b.bottom - l)
        path.lineTo(b.right, b.bottom - r)
        path.arcTo(b.right - 2 * r, b.bottom - 2 * r, b.right, b.bottom, 0f, 90f, false)
        path.lineTo(b.right - l, b.bottom)
        // bottom-left
        path.moveTo(b.left + l, b.bottom)
        path.lineTo(b.left + r, b.bottom)
        path.arcTo(b.left, b.bottom - 2 * r, b.left + 2 * r, b.bottom, 90f, 90f, false)
        path.lineTo(b.left, b.bottom - l)
        canvas.drawPath(path, bracketPaint)

        // Instruction text under the square.
        textLayout?.let { tl ->
            canvas.save()
            canvas.translate((width - tl.width) / 2f, rect.bottom + textGap)
            tl.draw(canvas)
            canvas.restore()
        }
    }
}
