package my.hinoki.booxreader.data.ui.reader.nativev2

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

class NativeReaderView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    private val textPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 98f
                color = Color.BLACK
                typeface = Typeface.DEFAULT
            }

    private val selectionPaint =
            Paint().apply {
                color = Color.BLACK
                alpha = 40 // Light grey on E-ink
            }

    private val handlePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

    private var content: CharSequence = ""
    private var layout: StaticLayout? = null

    // Selection state
    private var selectionStart: Int = -1
    private var selectionEnd: Int = -1
    private var isSelecting = false
    private var activeHandle: Int = 0 // 0: none, 1: start, 2: end

    private var onTouchTapListener: ((Float, Float) -> Unit)? = null
    private var onSelectionListener: ((Boolean, Float, Float) -> Unit)? = null
    private var onLinkClickListener: ((String) -> Unit)? = null

    // Magnifier state
    private var isMagnifying = false
    private var magnifierPos = PointF()
    private val magnifierRadius = 150f
    private val magnifierScale = 1.5f
    private val magnifierPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.LTGRAY
            }
    private val magnifierBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private var currentBackgroundColor: Int = Color.WHITE
    private var currentTextColor: Int = Color.BLACK
    private var currentLinkColor: Int = Color.BLUE

    fun setThemeColors(backgroundColor: Int, textColor: Int) {
        currentBackgroundColor = backgroundColor
        currentTextColor = textColor

        // Derive link color based on brightness
        val isDark = androidx.core.graphics.ColorUtils.calculateLuminance(backgroundColor) < 0.5
        currentLinkColor =
                if (isDark) {
                    Color.parseColor("#64B5F6") // Light Blue 300
                } else {
                    Color.parseColor("#1E88E5") // Blue 600
                }

        textPaint.color = textColor
        textPaint.linkColor = currentLinkColor
        handlePaint.color = textColor

        selectionPaint.color = textColor
        selectionPaint.alpha = 40

        magnifierPaint.color =
                if (Color.luminance(backgroundColor) > 0.5) Color.LTGRAY else Color.DKGRAY

        // Re-apply styles to current content when theme changes
        restyleLinks()

        invalidate()
    }

    fun setOnTouchTapListener(listener: (Float, Float) -> Unit) {
        this.onTouchTapListener = listener
    }

    fun setOnSelectionListener(listener: (Boolean, Float, Float) -> Unit) {
        this.onSelectionListener = listener
    }

    fun setOnLinkClickListener(listener: (String) -> Unit) {
        this.onLinkClickListener = listener
    }

    fun getSelectedText(): String? {
        if (!hasSelection()) return null
        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)
        return content.subSequence(start, end).toString()
    }

    fun hasSelection(): Boolean = selectionStart != -1 && selectionEnd != -1

    fun getSelectionLocator(): Locator? {
        val text = getSelectedText() ?: return null
        // start is unused in this simplified version
        return Locator(
                href = Url("native://current")!!, // Placeholder for native view
                mediaType = MediaType.TEXT,
                locations = Locator.Locations(progression = 0.0), // Simplified
                text = Locator.Text(highlight = text)
        )
    }

    private val gestureDetector =
            GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean = true

                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            if (selectionStart != -1) {
                                // If already selecting, a tap outside handles clears it
                                if (!isNear(e.x, e.y, selectionStart, true) &&
                                                !isNear(e.x, e.y, selectionEnd, false)
                                ) {
                                    selectionStart = -1
                                    selectionEnd = -1
                                    onSelectionListener?.invoke(false, 0f, 0f)
                                    invalidate()
                                    return true
                                }
                            }

                            // Link detection (Fuzzy hit area for small footnotes)
                            val linkUrl = findLinkAt(e.x, e.y)
                            if (linkUrl != null) {
                                onLinkClickListener?.invoke(linkUrl)
                                return true
                            }

                            onTouchTapListener?.invoke(e.x, e.y)
                            performClick()
                            return true
                        }

                        override fun onLongPress(e: MotionEvent) {
                            val offset = getOffsetForPosition(e.x, e.y)
                            if (offset != -1) {
                                // Hide existing menu if any
                                onSelectionListener?.invoke(false, 0f, 0f)

                                // Expert Selection: Select full word/phrase
                                val boundaries = getWordBoundaries(offset)
                                selectionStart = boundaries.first
                                selectionEnd = boundaries.second

                                activeHandle = 2 // Default to end handle for extension
                                isSelecting = true
                                isMagnifying = true
                                magnifierPos.set(e.x, e.y)

                                // Tactile feedback
                                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                                invalidate()
                                postInvalidateOnAnimation()
                                parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                    }
            )

    private fun getWordBoundaries(offset: Int): Pair<Int, Int> {
        if (content.isEmpty()) return offset to offset
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(content.toString())

        var end = iterator.following(offset)
        if (end == BreakIterator.DONE) end = content.length
        var start = iterator.previous()
        if (start == BreakIterator.DONE) start = 0

        // If it's a space or empty punctuation, fall back to single character
        val selected = content.subSequence(start, end).toString()
        if (selected.trim().isEmpty() && offset < content.length) {
            return offset to (offset + 1).coerceAtMost(content.length)
        }

        return start to end
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    fun setContent(text: CharSequence) {
        this.content =
                if (text is android.text.Spannable) text else android.text.SpannableString(text)
        restyleLinks()

        // Clear selection when content changes
        selectionStart = -1
        selectionEnd = -1
        requestLayout()
        invalidate()
    }

    private fun restyleLinks() {
        if (content !is android.text.Spannable) return
        val spannable = content as android.text.Spannable

        // 1. Remove existing styling spans created by us (to prevent accumulation and allow theme
        // updates)
        // We look for spans we likely added. Since we can't easily tag spans, we remove all
        // ForegroundColorSpan
        // and StyleSpan that perfectly overlap with URLSpans.
        // Or simpler: just clear all MetricAffectingSpans? No, that might kill other formatting.
        // Safe approach: Find URLSpans, then find other spans in same range.

        val links = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
        if (links.isEmpty()) return

        for (link in links) {
            val start = spannable.getSpanStart(link)
            val end = spannable.getSpanEnd(link)

            // Remove specific spans in this range
            val oldColors =
                    spannable.getSpans(
                            start,
                            end,
                            android.text.style.ForegroundColorSpan::class.java
                    )
            for (span in oldColors) spannable.removeSpan(span)

            val oldStyles = spannable.getSpans(start, end, android.text.style.StyleSpan::class.java)
            for (span in oldStyles) spannable.removeSpan(span)

            val oldSupers =
                    spannable.getSpans(start, end, android.text.style.SuperscriptSpan::class.java)
            for (span in oldSupers) spannable.removeSpan(span)

            val oldSizes =
                    spannable.getSpans(start, end, android.text.style.RelativeSizeSpan::class.java)
            for (span in oldSizes) spannable.removeSpan(span)

            // Remove old BackgroundColorSpan if any
            val oldBackgrounds =
                    spannable.getSpans(
                            start,
                            end,
                            android.text.style.BackgroundColorSpan::class.java
                    )
            for (span in oldBackgrounds) spannable.removeSpan(span)

            // 2. Apply new styling
            // HIGHLIGHT: Add a subtle background to make it absolutely clear where the link is
            val highlightColor =
                    if (Color.luminance(currentLinkColor) > 0.5) {
                        Color.argb(40, 0, 0, 0) // Dark highlight for light links
                    } else {
                        Color.argb(40, 255, 255, 255) // Light highlight for dark links
                    }
            spannable.setSpan(
                    android.text.style.BackgroundColorSpan(highlightColor),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            spannable.setSpan(
                    android.text.style.ForegroundColorSpan(currentLinkColor),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Flexible footnote detection: any short text sequence (1-4 chars) with digits
            val linkText = spannable.subSequence(start, end).toString().trim()
            val hasDigits = linkText.any { it.isDigit() }
            if (linkText.length <= 5 && hasDigits) {
                spannable.setSpan(
                        FootnoteSuperscriptSpan(0.7f),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                        android.text.style.RelativeSizeSpan(0.6f),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    fun setTextSize(size: Float) {
        textPaint.textSize = size
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = width - paddingLeft - paddingRight
        if (availableWidth > 0 && content.isNotEmpty()) {
            layout =
                    StaticLayout.Builder.obtain(
                                    content,
                                    0,
                                    content.length,
                                    textPaint,
                                    availableWidth
                            )
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(0f, 1.4f)
                            .setIncludePad(false)
                            .build()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(currentBackgroundColor)

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        val l = layout ?: return

        // 1. Draw Selection Background
        if (selectionStart != -1 && selectionEnd != -1) {
            val start = min(selectionStart, selectionEnd)
            val end = max(selectionStart, selectionEnd)

            val startLine = l.getLineForOffset(start)
            val endLine = l.getLineForOffset(end)

            for (line in startLine..endLine) {
                val lineStart = if (line == startLine) start else l.getLineStart(line)
                val lineEnd = if (line == endLine) end else l.getLineEnd(line)

                val left = if (line == startLine) l.getPrimaryHorizontal(lineStart) else 0f
                val right =
                        if (line == endLine) l.getPrimaryHorizontal(lineEnd) else l.width.toFloat()

                // Use FontMetrics for tight vertical bounds (ignores line spacing multiplier)
                val baseline = l.getLineBaseline(line).toFloat()
                val fm = textPaint.fontMetrics
                val top = baseline + fm.ascent
                val bottom = baseline + fm.descent

                canvas.drawRect(left, top, right, bottom, selectionPaint)
            }

            // 2. Draw Handles (Premium Pill Style)
            drawHandles(canvas, start, end)
        }

        // 3. Draw Text
        l.draw(canvas)
        canvas.restore()

        // 4. Draw Magnifier Overlay
        if (isMagnifying && (selectionStart != -1 || selectionEnd != -1)) {
            val offset =
                    if (activeHandle == 1) min(selectionStart, selectionEnd)
                    else max(selectionStart, selectionEnd)
            val line = l.getLineForOffset(offset)
            val focusX = l.getPrimaryHorizontal(offset) + paddingLeft
            val focusY =
                    l.getLineBottom(line).toFloat() -
                            (l.getLineBottom(line) - l.getLineTop(line)) / 2f + paddingTop

            val magnifierX = magnifierPos.x
            val magnifierY = magnifierPos.y - magnifierRadius - 100f // Offset above finger

            val path =
                    Path().apply {
                        addCircle(magnifierX, magnifierY, magnifierRadius, Path.Direction.CCW)
                    }
            canvas.save()
            canvas.clipPath(path)

            // Draw background for magnifier
            canvas.drawColor(currentBackgroundColor)

            // Scale and Translate to focus area
            canvas.translate(magnifierX, magnifierY)
            canvas.scale(magnifierScale, magnifierScale)
            canvas.translate(-focusX, -focusY)

            // Re-draw selection and text into magnifier
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

            val start = min(selectionStart, selectionEnd)
            val end = max(selectionStart, selectionEnd)
            val startLine = l.getLineForOffset(start)
            val endLine = l.getLineForOffset(end)
            for (lineIdx in startLine..endLine) {
                val lineStart = if (lineIdx == startLine) start else l.getLineStart(lineIdx)
                val lineEnd = if (lineIdx == endLine) end else l.getLineEnd(lineIdx)
                val left = if (lineIdx == startLine) l.getPrimaryHorizontal(lineStart) else 0f
                val right =
                        if (lineIdx == endLine) l.getPrimaryHorizontal(lineEnd)
                        else l.width.toFloat()

                // Use FontMetrics in magnifier too
                val baseline = l.getLineBaseline(lineIdx).toFloat()
                val fm = textPaint.fontMetrics
                val top = baseline + fm.ascent
                val bottom = baseline + fm.descent

                canvas.drawRect(left, top, right, bottom, selectionPaint)
            }
            l.draw(canvas)
            canvas.restore()

            canvas.restore()

            // Draw magnifier border
            canvas.drawCircle(magnifierX, magnifierY, magnifierRadius, magnifierPaint)
        }
    }

    private class FootnoteSuperscriptSpan(private val shiftMultiplier: Float) :
            android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            applyShift(tp)
        }

        override fun updateMeasureState(tp: TextPaint) {
            applyShift(tp)
        }

        private fun applyShift(tp: TextPaint) {
            tp.baselineShift += (tp.ascent() * shiftMultiplier).toInt()
        }
    }

    private fun drawHandles(canvas: Canvas, start: Int, end: Int) {
        val l = layout ?: return
        val handleWidth = 4f
        val handleBallRadius = 18f

        val minOff = min(start, end)
        val maxOff = max(start, end)

        // Start handle
        val sLine = l.getLineForOffset(minOff)
        val sX = l.getPrimaryHorizontal(minOff)
        val sBaseline = l.getLineBaseline(sLine).toFloat()
        val fm = textPaint.fontMetrics
        val sTop = sBaseline + fm.ascent
        val sBottom = sBaseline + fm.descent

        canvas.drawRect(sX - handleWidth / 2, sTop, sX + handleWidth / 2, sBottom, handlePaint)
        canvas.drawCircle(sX, sTop, handleBallRadius, handlePaint)

        // End handle
        val eLine = l.getLineForOffset(maxOff)
        val eX = l.getPrimaryHorizontal(maxOff)
        val eBaseline = l.getLineBaseline(eLine).toFloat()
        val eTop = eBaseline + fm.ascent
        val eBottom = eBaseline + fm.descent

        canvas.drawRect(eX - handleWidth / 2, eTop, eX + handleWidth / 2, eBottom, handlePaint)
        canvas.drawCircle(eX, eBottom, handleBallRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // Pass to gesture detector primarily for LongPress and SingleTap
        val gestureHandled = gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // If we are touching near a handle, start dragging it
                if (selectionStart != -1) {
                    val start = min(selectionStart, selectionEnd)
                    val end = max(selectionStart, selectionEnd)

                    if (isNear(x, y, start, true)) {
                        activeHandle = 1
                        isSelecting = true
                        onSelectionListener?.invoke(false, 0f, 0f)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    } else if (isNear(x, y, end, false)) {
                        activeHandle = 2
                        isSelecting = true
                        onSelectionListener?.invoke(false, 0f, 0f)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    // Even if not exactly near a handle, if selection exists, don't let parent
                    // steal swipe
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                // Don't return true here yet, let gesture detector handle it
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSelecting) {
                    parent?.requestDisallowInterceptTouchEvent(
                            true
                    ) // Aggressively block parent (ViewPager/Pager)
                    isMagnifying = true
                    magnifierPos.set(x, y)

                    var offset = getOffsetForPosition(x, y)
                    if (offset != -1) {
                        // Smart handle selection: if we just started dragging from a long press,
                        // we can swap handle if the user moves to the left of the start
                        if (activeHandle == 2 && offset < selectionStart) {
                            val temp = selectionStart
                            selectionStart = selectionEnd
                            selectionEnd = temp
                            activeHandle = 1
                        } else if (activeHandle == 1 && offset > selectionEnd) {
                            val temp = selectionStart
                            selectionStart = selectionEnd
                            selectionEnd = temp
                            activeHandle = 2
                        }

                        if (activeHandle == 1) {
                            selectionStart = offset
                        } else if (activeHandle == 2) {
                            selectionEnd = offset
                        }

                        // Force minimum 1 character selection
                        if (selectionStart == selectionEnd) {
                            if (activeHandle == 1) {
                                selectionStart = (selectionEnd - 1).coerceAtLeast(0)
                            } else {
                                selectionEnd = (selectionStart + 1).coerceAtMost(content.length)
                            }
                        }

                        invalidate()
                        postInvalidateOnAnimation()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSelecting) {
                    isSelecting = false
                    activeHandle = 0
                    isMagnifying = false
                    postInvalidateOnAnimation()

                    // Normalize selection after dragging for consistent state
                    val s = min(selectionStart, selectionEnd)
                    val e = max(selectionStart, selectionEnd)

                    // Final safety check for 1-char
                    if (s == e) {
                        selectionStart = s
                        selectionEnd = (s + 1).coerceAtMost(content.length)
                    } else {
                        selectionStart = s
                        selectionEnd = e
                    }

                    // Trigger selection menu AFTER finger is lifted
                    val l = layout
                    if (l != null && selectionEnd != -1) {
                        try {
                            val line = l.getLineForOffset(selectionEnd)
                            val menuX = l.getPrimaryHorizontal(selectionEnd) + paddingLeft
                            val menuY = l.getLineTop(line).toFloat() + paddingTop
                            onSelectionListener?.invoke(true, menuX, menuY)
                        } catch (ex: Exception) {
                            // Boundary safe
                        }
                    }
                    return true
                }
            }
        }
        return gestureHandled || true
    }

    fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
        activeHandle = 0
        isSelecting = false
        onSelectionListener?.invoke(false, 0f, 0f)
        invalidate()
    }

    private fun isNear(x: Float, y: Float, offset: Int, isStart: Boolean): Boolean {
        if (offset == -1) return false
        val l = layout ?: return false
        val line = l.getLineForOffset(offset)

        // Target the center of the balls
        val ox = l.getPrimaryHorizontal(offset) + paddingLeft
        val baseline = l.getLineBaseline(line).toFloat()
        val fm = textPaint.fontMetrics
        val oy =
                if (isStart) baseline + fm.ascent + paddingTop
                else baseline + fm.descent + paddingTop

        // Massive hit area for handles: 180px radius (32400 squared)
        return (x - ox) * (x - ox) + (y - oy) * (y - oy) < 32400
    }

    private fun getOffsetForPosition(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val relativeY = (y - paddingTop).coerceIn(0f, l.height.toFloat())
        val line = l.getLineForVertical(relativeY.toInt())

        val relativeX = x - paddingLeft

        // Aggressive horizontal snapping for edges (100px buffer for easier selection at the very
        // end/start)
        val snapThreshold = (l.width * 0.1f).coerceAtMost(100f)
        if (relativeX > l.width - snapThreshold) {
            return l.getLineEnd(line)
        }
        if (relativeX < snapThreshold) {
            return l.getLineStart(line)
        }

        return l.getOffsetForHorizontal(line, relativeX.coerceIn(0f, l.width.toFloat()))
    }

    private fun findLinkAt(x: Float, y: Float): String? {
        val l = layout ?: return null
        val density = resources.displayMetrics.density
        val radius = 12f * density // ~12dp radius

        // Try several points around the tap to increase hit area
        // We prioritize the center point
        val offsets = mutableSetOf<Int>()
        offsets.add(getOffsetForPosition(x, y))
        offsets.add(getOffsetForPosition(x - radius, y))
        offsets.add(getOffsetForPosition(x + radius, y))
        offsets.add(getOffsetForPosition(x, y - radius))
        offsets.add(getOffsetForPosition(x, y + radius))

        if (content is android.text.Spanned) {
            val spanned = content as android.text.Spanned
            for (offset in offsets) {
                if (offset != -1) {
                    val spans =
                            spanned.getSpans(offset, offset, android.text.style.URLSpan::class.java)
                    if (spans.isNotEmpty()) {
                        return spans[0].url
                    }
                }
            }
            return findLinkByBounds(x, y, spanned, l)
        }
        return null
    }

    private fun findLinkByBounds(
            x: Float,
            y: Float,
            spanned: android.text.Spanned,
            l: Layout
    ): String? {
        val density = resources.displayMetrics.density
        val xPad = 14f * density
        val relX = x - paddingLeft
        val relY = y - paddingTop
        if (relY < 0 || relX < 0) return null

        val line = l.getLineForVertical(relY.toInt())
        val lineStart = l.getLineStart(line)
        val lineEnd = l.getLineEnd(line)
        val spans = spanned.getSpans(lineStart, lineEnd, android.text.style.URLSpan::class.java)
        if (spans.isEmpty()) return null

        for (span in spans) {
            val spanStart = max(spanned.getSpanStart(span), lineStart)
            val spanEnd = min(spanned.getSpanEnd(span), lineEnd)
            if (spanStart >= spanEnd) continue
            var left = l.getPrimaryHorizontal(spanStart)
            var right = l.getPrimaryHorizontal(spanEnd)
            if (left > right) {
                val tmp = left
                left = right
                right = tmp
            }
            if (relX + xPad >= left && relX - xPad <= right) {
                return span.url
            }
        }
        return null
    }
}
