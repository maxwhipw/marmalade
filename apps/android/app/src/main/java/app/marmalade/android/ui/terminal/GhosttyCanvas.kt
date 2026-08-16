// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/ui/terminal/TerminalCanvas.kt
//
// Ported: the metrics + integer cell snap, the background and text run passes,
// kitty image placement, the cursor (chuchu drew only a filled block; the
// bar / underline / hollow shapes and the blink are ours, fed by
// cursor_style.zig), the frame-coalesced drag-to-scroll,
// the whole-sp pinch zoom, and the gesture multiplexer — long-press selection,
// selection drag, edge auto-scroll, double-tap word select and the app-mouse
// drag branch. NOT ported: the preview "fit to canvas" mode, and the raw
// `terminalHandle` parameter (a data race; selection text comes back through
// [GhosttyTerminalEngine.selectionText] instead). The faces are ours (bundled
// JetBrains Mono + Symbols Nerd Font Mono + Noto Sans Symbols 2, see
// CREDITS.md), not chuchu's; the run segmentation, paint choice and selection
// maths live in `terminal/` so they can be unit-tested.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.ui.terminal

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.ViewConfiguration
import androidx.annotation.FontRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.marmalade.android.R
import app.marmalade.android.terminal.GhosttyMouseAction
import app.marmalade.android.terminal.GlyphCoverage
import app.marmalade.android.terminal.TerminalCursorGeometry
import app.marmalade.android.terminal.TerminalCursorStyle
import app.marmalade.android.terminal.TerminalPaintChoice
import app.marmalade.android.terminal.TerminalRuns
import app.marmalade.android.terminal.TerminalSelection
import app.marmalade.android.terminal.TerminalSelections
import app.marmalade.android.terminal.TerminalSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Draws a libghostty grid snapshot, and turns touches into scrolls, taps and
 * font-size changes.
 *
 * The renderer owns the geometry: it measures the monospace cell, snaps it to
 * whole pixels (sub-pixel drift accumulates across a row and misaligns kitty
 * images against ghostty's own integer grid), divides the canvas by it and
 * reports the resulting grid through [onGridChange]. The screen forwards that
 * to BOTH the emulator and the daemon's PTY.
 *
 * Selection is **hoisted**: the caller owns it, so it can also draw the drag
 * handles and ask the engine for the text. The canvas only decides which cells
 * the gestures mean.
 *
 * @param snapshot null before the first frame — the canvas paints [defaultBg].
 * @param cursorStyle the shape and blink mode the running program selected
 *   (DECSCUSR). Blinking is honoured only while [focused]: an unfocused
 *   terminal that keeps flashing is just a battery drain in the corner of
 *   the eye, and no animation is started for a steady cursor.
 * @param focused whether the terminal's window has focus.
 * @param onGridChange (cols, rows, cellWidthPx, cellHeightPx) whenever the
 *   fitted grid changes.
 * @param onScroll viewport rows; negative scrolls back into scrollback.
 * @param onPrimaryClick a tap, as a mouse click for a mouse-tracking app.
 * @param onAppMouseDrag a long-press drag while the app does its own selection
 *   ([TerminalSnapshot.appHandlesSelectionDrag]) — press/motion/release.
 * @param onSelectionDragChange true while a long-press selection drag owns the
 *   pointer. Reported once per gesture, not per frame: the caller uses it to
 *   suppress the floating text-action menu and to raise the magnifier, both of
 *   which are gesture-scoped decisions.
 * @param selectionColor a **translucent** wash; it is composited onto each
 *   selected cell's own background rather than replacing it.
 */
@Composable
fun GhosttyCanvas(
    snapshot: TerminalSnapshot?,
    cursorStyle: TerminalCursorStyle = TerminalCursorStyle.Default,
    focused: Boolean = true,
    modifier: Modifier = Modifier,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    defaultBg: Color,
    cursorColor: Color,
    cursorTextColor: Color,
    onGridChange: (cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    onTap: () -> Unit,
    onPrimaryClick: (x: Float, y: Float) -> Unit,
    onScroll: (delta: Int, x: Float, y: Float) -> Unit,
    selection: TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onSelectionGeometry: (TerminalSelectionGeometry?) -> Unit,
    selectionColor: Color,
    onAppMouseDrag: (action: Int, x: Float, y: Float) -> Unit,
    onSelectionDragChange: (dragging: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastReportedGrid by remember { mutableStateOf(0 to 0) }
    val viewConfiguration = remember(context) { ViewConfiguration.get(context) }
    val touchSlopPx = remember(viewConfiguration) { viewConfiguration.scaledTouchSlop.toFloat() }
    // A long press must tolerate more wobble than a scroll does, or holding
    // still enough to start a selection becomes a test of steady hands.
    val longPressSlopPx = remember(touchSlopPx) { touchSlopPx * LONG_PRESS_SLOP_FACTOR }
    val longPressTimeoutMs = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    val doubleTapTimeoutMs = remember { ViewConfiguration.getDoubleTapTimeout().toLong() }
    val doubleTapSlopPx = remember(viewConfiguration) {
        viewConfiguration.scaledDoubleTapSlop.toFloat()
    }
    val autoScrollEdgeZonePx = with(density) { AUTO_SCROLL_EDGE_ZONE_DP.dp.toPx() }

    // The bundled faces (see CREDITS.md): JetBrains Mono for text, Symbols Nerd
    // Font Mono for the private-use icon glyphs real prompts emit, Noto Sans
    // Symbols 2 for the non-PUA symbols (box drawing extras, misc technical).
    // Emoji stays the system default face — that is what carries Android's
    // color-emoji chain and its ZWJ shaping. Each falls back to a system face if
    // the resource cannot be loaded, so a missing font degrades to tofu, never
    // to a crash.
    val primaryTypeface = rememberFont(R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
    val symbolsTypeface = rememberFont(R.font.symbols_nerd_font_mono_regular)
    val fallbackTypeface = rememberFont(R.font.noto_sans_symbols2_regular) ?: Typeface.DEFAULT
    val primaryTextPaint = remember(fontSizePx, primaryTypeface) {
        terminalPaint(primaryTypeface, fontSizePx)
    }
    val symbolsTextPaint = remember(fontSizePx, symbolsTypeface) {
        terminalPaint(symbolsTypeface ?: Typeface.MONOSPACE, fontSizePx)
    }
    val emojiTextPaint = remember(fontSizePx) { terminalPaint(Typeface.DEFAULT, fontSizePx) }
    val fallbackTextPaint = remember(fontSizePx, fallbackTypeface) {
        terminalPaint(fallbackTypeface, fontSizePx)
    }
    val bgPaint = remember { Paint().apply { isAntiAlias = false; style = Paint.Style.FILL } }
    val cursorPaint = remember { Paint().apply { isAntiAlias = false; style = Paint.Style.FILL } }
    val cursorStrokePx = with(density) { CURSOR_STROKE_DP.dp.toPx() }
    val cursorThinPx = with(density) { CURSOR_THIN_DP.dp.toPx() }

    // Blink is a plain toggling flag, not an animation: a steady cursor is the
    // common case and must cost nothing, so the effect returns immediately
    // unless the app asked for blink AND we hold focus. Keying on
    // [blinkActive] restarts the loop in phase whenever that flips, so the
    // cursor is always solid the instant it stops blinking.
    var blinkOn by remember { mutableStateOf(true) }
    val blinkActive = cursorStyle.blinking && focused
    LaunchedEffect(blinkActive) {
        if (!blinkActive) {
            blinkOn = true
            return@LaunchedEffect
        }
        while (true) {
            delay(CURSOR_BLINK_PHASE_MS)
            blinkOn = !blinkOn
        }
    }

    val coverage = remember(primaryTextPaint, symbolsTextPaint, fallbackTextPaint) {
        object : GlyphCoverage {
            override fun primaryHasGlyph(glyph: String) = primaryTextPaint.hasGlyph(glyph)
            // When the symbols face failed to load its paint is the monospace
            // fallback, which never covers the private-use area — so claiming
            // coverage off it cannot route a PUA glyph to the wrong face.
            override fun symbolsHasGlyph(glyph: String) =
                symbolsTypeface != null && symbolsTextPaint.hasGlyph(glyph)
            override fun emojiHasGlyph(glyph: String) = emojiTextPaint.hasGlyph(glyph)
            override fun fallbackHasGlyph(glyph: String) = fallbackTextPaint.hasGlyph(glyph)
        }
    }
    // `hasGlyph` is a font-table lookup — far too slow to run per cell per frame.
    val singleGlyphCache = remember(coverage) {
        HashMap<Int, String>(256).apply { for (cp in 33..126) this[cp] = cp.toChar().toString() }
    }
    val singlePaintChoiceCache = remember(coverage) { HashMap<Int, Int>(256) }
    val clusterPaintChoiceCache = remember(coverage) { HashMap<String, Int>(64) }

    val fontMetrics = primaryTextPaint.fontMetrics
    val measuredHeight = fontMetrics.descent - fontMetrics.ascent
    val measuredWidth = primaryTextPaint.measureText("M")
    val baselineOffset = -fontMetrics.ascent
    // Snap to whole pixels so the renderer agrees with ghostty's internal grid.
    val cellWidthInt = max(1, ceil(if (measuredWidth > 1f) measuredWidth else 8f).toInt())
    val cellHeightInt = max(1, ceil(if (measuredHeight > 1f) measuredHeight else 16f).toInt())
    val cellWidth = cellWidthInt.toFloat()
    val cellHeight = cellHeightInt.toFloat()

    val defaultBgArgb = defaultBg.toArgb()
    val cursorColorArgb = cursorColor.toArgb()
    val cursorTextColorArgb = cursorTextColor.toArgb()

    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnPrimaryClick = rememberUpdatedState(onPrimaryClick)
    val currentOnScroll = rememberUpdatedState(onScroll)
    val currentOnFontSizeChange = rememberUpdatedState(onFontSizeChange)
    val currentFontSizeSp = rememberUpdatedState(fontSizeSp)
    val currentCellWidth = rememberUpdatedState(cellWidth)
    val currentCellHeight = rememberUpdatedState(cellHeight)
    val currentHaptics = rememberUpdatedState(haptics)
    val currentTouchSlopPx = rememberUpdatedState(touchSlopPx)
    val currentLongPressSlopPx = rememberUpdatedState(longPressSlopPx)
    val currentLongPressTimeoutMs = rememberUpdatedState(longPressTimeoutMs)
    val currentDoubleTapTimeoutMs = rememberUpdatedState(doubleTapTimeoutMs)
    val currentDoubleTapSlopPx = rememberUpdatedState(doubleTapSlopPx)
    val currentAutoScrollEdgeZonePx = rememberUpdatedState(autoScrollEdgeZonePx)
    val currentSnapshot = rememberUpdatedState(snapshot)
    val currentSelection = rememberUpdatedState(selection)
    val currentOnSelectionChange = rememberUpdatedState(onSelectionChange)
    val currentOnAppMouseDrag = rememberUpdatedState(onAppMouseDrag)
    val currentOnSelectionDragChange = rememberUpdatedState(onSelectionDragChange)

    // Selection indices are VIEWPORT-relative, so a scroll renames every cell.
    // The baseline is the viewportScrollY the selection was made at; when the
    // snapshot's differs, the selection is shifted back onto its own text.
    var selectionScrollBaseline by remember { mutableStateOf<Int?>(null) }
    // True while an edge auto-scroll is running: then the focus is the finger
    // and must NOT be remapped, which is how the selection grows.
    var autoScrollingSelection by remember { mutableStateOf(false) }
    val lastTap = remember { LastTap() }

    val hasSelection = selection != null
    LaunchedEffect(hasSelection) {
        selectionScrollBaseline = if (hasSelection) currentSnapshot.value?.viewportScrollY else null
    }

    LaunchedEffect(snapshot, selection, cellWidth, cellHeight) {
        val snap = snapshot
        val sel = selection
        if (snap == null || sel == null) {
            onSelectionGeometry(null)
            return@LaunchedEffect
        }
        val baseline = selectionScrollBaseline
        if (baseline != null && baseline != snap.viewportScrollY) {
            selectionScrollBaseline = snap.viewportScrollY
            onSelectionChange(
                TerminalSelections.remapForViewportScroll(
                    selection = sel,
                    cols = snap.cols,
                    baselineScrollY = baseline,
                    currentScrollY = snap.viewportScrollY,
                    anchorOnly = autoScrollingSelection,
                ),
            )
            return@LaunchedEffect
        }
        onSelectionGeometry(selectionGeometry(snap, sel, cellWidth, cellHeight))
    }

    // Coalesce per frame: a fast flick produces far more pointer events than
    // frames, and each scroll costs a native call plus a grid decode.
    val scrollDeltaChannel = remember { Channel<ScrollDelta>(capacity = Channel.UNLIMITED) }
    LaunchedEffect(scrollDeltaChannel) {
        while (isActive) {
            val first = scrollDeltaChannel.receive()
            var accumulated = first.delta
            var latest = first
            while (true) {
                val next = scrollDeltaChannel.tryReceive().getOrNull() ?: break
                accumulated += next.delta
                latest = next
            }
            if (accumulated != 0) currentOnScroll.value(accumulated, latest.x, latest.y)
            withFrameNanos { }
        }
    }

    LaunchedEffect(canvasSize, cellWidth, cellHeight) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = max(1, floor(canvasSize.width / cellWidth).toInt())
        val rows = max(1, floor(canvasSize.height / cellHeight).toInt())
        val grid = cols to rows
        if (grid != lastReportedGrid) {
            lastReportedGrid = grid
            onGridChange(cols, rows, cellWidthInt, cellHeightInt)
        }
    }

    val canvasModifier = modifier
        .fillMaxSize()
        .onSizeChanged { canvasSize = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragRemainder = 0f
                var startPinchDistance: Float? = null
                var anchorFontSp = 0f
                var lastSentSp = 0f
                var didPinch = false
                var didDrag = false
                var selectionCleared = false
                var lastSinglePointerId = down.id
                var dragMode = DragMode.None
                var lastEventUptime = down.uptimeMillis
                val longPressDeadline = down.uptimeMillis + currentLongPressTimeoutMs.value
                var lastPointerPos = down.position
                var autoScrollDir = 0
                // The long press fires at most once. Without this, a press that
                // cannot start a selection (no frame yet, or a grid with no
                // cells) would re-arm an already-expired deadline and spin.
                var longPressFired = false

                try {
                while (true) {
                    // Three ways to wait. Before anything has happened we wait
                    // only until the long-press deadline (a timeout IS the long
                    // press); while auto-scrolling we wake on a cadence to keep
                    // scrolling with the finger still; otherwise we just block.
                    val idle =
                        dragMode == DragMode.None && !didDrag && !didPinch && !longPressFired
                    val event = when {
                        idle -> withTimeoutOrNull(
                            (longPressDeadline - lastEventUptime).coerceAtLeast(1L),
                        ) { awaitPointerEvent() }
                        dragMode == DragMode.ClientSelection && autoScrollDir != 0 ->
                            withTimeoutOrNull(AUTO_SCROLL_INTERVAL_MS) { awaitPointerEvent() }
                        else -> awaitPointerEvent()
                    }

                    if (event == null) {
                        if (dragMode == DragMode.None) {
                            longPressFired = true
                            val snap = currentSnapshot.value ?: continue
                            if (snap.appHandlesSelectionDrag) {
                                // The running app is doing its own selection
                                // (vim visual mode, tmux copy mode): forward the
                                // press instead of selecting on top of it.
                                currentOnAppMouseDrag.value(
                                    GhosttyMouseAction.PRESS,
                                    down.position.x,
                                    down.position.y,
                                )
                                currentHaptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
                                didDrag = true
                                dragMode = DragMode.AppMouse
                            } else {
                                val cell = TerminalSelections.cellAt(
                                    snap,
                                    down.position.x,
                                    down.position.y,
                                    currentCellWidth.value,
                                    currentCellHeight.value,
                                )
                                if (cell != null) {
                                    currentOnSelectionChange.value(TerminalSelection(cell, cell))
                                    currentHaptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragMode = DragMode.ClientSelection
                                    currentOnSelectionDragChange.value(true)
                                }
                            }
                            continue
                        }
                        // Auto-scroll tick: how deep into the edge zone the
                        // finger is sets the speed, so the far edge is fast.
                        val depth = if (autoScrollDir > 0) {
                            lastPointerPos.y - (canvasSize.height - currentAutoScrollEdgeZonePx.value)
                        } else {
                            currentAutoScrollEdgeZonePx.value - lastPointerPos.y
                        }.coerceAtLeast(0f)
                        val rows = (depth / currentCellHeight.value).toInt()
                            .coerceIn(1, MAX_AUTO_SCROLL_ROWS)
                        scrollDeltaChannel.trySend(
                            ScrollDelta(autoScrollDir * rows, lastPointerPos.x, lastPointerPos.y),
                        )
                        continue
                    }

                    lastEventUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventUptime
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        autoScrollDir = 0
                        autoScrollingSelection = false
                        val releasedMode = dragMode
                        dragMode = DragMode.None
                        if (releasedMode == DragMode.AppMouse) {
                            currentOnAppMouseDrag.value(
                                GhosttyMouseAction.RELEASE,
                                lastPointerPos.x,
                                lastPointerPos.y,
                            )
                        }
                        if (releasedMode != DragMode.None) break
                        if (!didPinch && !didDrag) {
                            onTapRelease(
                                downPosition = down.position,
                                tapTime = lastEventUptime,
                                lastTap = lastTap,
                                snapshot = currentSnapshot.value,
                                selection = currentSelection.value,
                                cellWidth = currentCellWidth.value,
                                cellHeight = currentCellHeight.value,
                                doubleTapTimeoutMs = currentDoubleTapTimeoutMs.value,
                                doubleTapSlopPx = currentDoubleTapSlopPx.value,
                                haptics = currentHaptics.value,
                                onSelectionChange = currentOnSelectionChange.value,
                                onPrimaryClick = currentOnPrimaryClick.value,
                                onTap = currentOnTap.value,
                            )
                        }
                        break
                    }

                    if (pressed.size >= 2) {
                        didPinch = true
                        val distance = hypot(
                            (pressed[0].position.x - pressed[1].position.x).toDouble(),
                            (pressed[0].position.y - pressed[1].position.y).toDouble(),
                        ).toFloat()
                        if (startPinchDistance == null && distance > 0f) {
                            startPinchDistance = distance
                            anchorFontSp = currentFontSizeSp.value
                            lastSentSp = round(anchorFontSp)
                        }
                        val startDistance = startPinchDistance
                        if (startDistance != null && distance > 0f) {
                            // Whole sp steps: the cell is snapped to whole pixels,
                            // so fractional sizes buy nothing but re-layout churn.
                            val steppedSp = round(anchorFontSp * (distance / startDistance))
                                .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
                            if (steppedSp != lastSentSp) {
                                currentOnFontSizeChange.value(steppedSp)
                                currentHaptics.value.performHapticFeedback(
                                    HapticFeedbackType.TextHandleMove,
                                )
                                lastSentSp = steppedSp
                            }
                        }
                        pressed.forEach { if (it.position != it.previousPosition) it.consume() }
                        continue
                    }

                    startPinchDistance = null
                    val change = pressed.firstOrNull { it.id == lastSinglePointerId }
                        ?: pressed.first().also { lastSinglePointerId = it.id }
                    lastPointerPos = change.position

                    if (dragMode == DragMode.AppMouse) {
                        autoScrollDir = 0
                        autoScrollingSelection = false
                        if (change.position != change.previousPosition) {
                            currentOnAppMouseDrag.value(
                                GhosttyMouseAction.MOTION,
                                change.position.x,
                                change.position.y,
                            )
                            change.consume()
                        }
                        continue
                    }

                    val snap = currentSnapshot.value
                    val selectedCell = snap?.let {
                        TerminalSelections.cellAt(
                            it,
                            change.position.x,
                            change.position.y,
                            currentCellWidth.value,
                            currentCellHeight.value,
                        )
                    }
                    if (dragMode == DragMode.ClientSelection && selectedCell != null) {
                        val existing = currentSelection.value
                        if (existing == null || existing.focusIndex != selectedCell) {
                            currentOnSelectionChange.value(
                                (existing ?: TerminalSelection(selectedCell, selectedCell))
                                    .copy(focusIndex = selectedCell),
                            )
                        }
                        autoScrollDir = when {
                            change.position.y < currentAutoScrollEdgeZonePx.value -> -1
                            change.position.y >
                                canvasSize.height - currentAutoScrollEdgeZonePx.value -> 1
                            else -> 0
                        }
                        autoScrollingSelection = autoScrollDir != 0
                        if (change.position != change.previousPosition) change.consume()
                        continue
                    }

                    val dragX = change.position.x - change.previousPosition.x
                    val dragY = change.position.y - change.previousPosition.y
                    val movedDistance = hypot(
                        (change.position.x - down.position.x).toDouble(),
                        (change.position.y - down.position.y).toDouble(),
                    ).toFloat()
                    // Before any mode is chosen the finger is still a long-press
                    // candidate, so it gets the looser slop.
                    val abortSlopPx = if (dragMode == DragMode.None) {
                        currentLongPressSlopPx.value
                    } else {
                        currentTouchSlopPx.value
                    }
                    if (movedDistance > abortSlopPx) {
                        didDrag = true
                        // Scrolling away from a selection dismisses it, once.
                        if (currentSelection.value != null && !selectionCleared) {
                            currentOnSelectionChange.value(null)
                            selectionCleared = true
                        }
                    }
                    autoScrollDir = 0
                    autoScrollingSelection = false
                    // Ignore mostly-horizontal movement: there is nothing to pan to.
                    if (abs(dragY) > abs(dragX) * VERTICAL_INTENT_RATIO) {
                        dragRemainder += dragY / currentCellHeight.value
                    }
                    if (didDrag && abs(dragRemainder) >= 1f) {
                        val delta = dragRemainder.toInt()
                        dragRemainder -= delta
                        // Drag down = look further back, hence the negation.
                        if (delta != 0) {
                            scrollDeltaChannel.trySend(
                                ScrollDelta(-delta, change.position.x, change.position.y),
                            )
                        }
                    }
                    if (change.position != change.previousPosition) change.consume()
                }
                } finally {
                    autoScrollingSelection = false
                    // Every exit — release, break, cancellation — ends the
                    // drag. Reporting it here rather than per branch is what
                    // stops a cancelled gesture from leaving the magnifier up.
                    currentOnSelectionDragChange.value(false)
                    // A cancelled gesture (the composable leaves, a parent takes
                    // the pointer) must still release the button, or the app
                    // stays in a drag it can never end.
                    if (dragMode == DragMode.AppMouse) {
                        currentOnAppMouseDrag.value(
                            GhosttyMouseAction.RELEASE,
                            lastPointerPos.x,
                            lastPointerPos.y,
                        )
                    }
                }
            }
        }

    Canvas(modifier = canvasModifier) {
        drawRect(color = Color(snapshot?.defaultBgArgb ?: defaultBgArgb))
        if (snapshot == null || snapshot.cols <= 0 || snapshot.rows <= 0) return@Canvas
        val cols = snapshot.cols
        val rows = snapshot.rows
        // Selection is a translucent wash composited onto each cell's own
        // background (see TerminalRuns.SelectionPaint), so it folds into the
        // existing background run pass instead of needing a pass of its own.
        val selectionPaint = selection
            ?.normalized(snapshot.codepoints.size)
            ?.let { TerminalRuns.SelectionPaint(it, selectionColor.toArgb()) }

        fun paintChoiceAt(index: Int): Int {
            val cp = snapshot.codepoints[index]
            val extras = snapshot.graphemeExtras[index]
            return if (extras == null || extras.isEmpty()) {
                if (cp in 0x21..0x7E) {
                    TerminalPaintChoice.PRIMARY
                } else {
                    singlePaintChoiceCache.getOrPut(cp) {
                        val glyph = singleGlyphCache.getOrPut(cp) { String(Character.toChars(cp)) }
                        TerminalPaintChoice.choose(glyph, coverage)
                    }
                }
            } else {
                val glyph = TerminalRuns.glyphAt(snapshot, index)
                clusterPaintChoiceCache.getOrPut(glyph) {
                    TerminalPaintChoice.choose(glyph, coverage)
                }
            }
        }

        fun paintFor(choice: Int): Paint = when (choice) {
            TerminalPaintChoice.SYMBOLS -> symbolsTextPaint
            TerminalPaintChoice.FALLBACK -> fallbackTextPaint
            TerminalPaintChoice.EMOJI -> emojiTextPaint
            else -> primaryTextPaint
        }

        drawIntoCanvas { canvas ->
            val nCanvas = canvas.nativeCanvas

            for (row in 0 until rows) {
                val rowStart = row * cols
                val rowEnd = rowStart + cols
                val y = row * cellHeight
                val baseline = y + baselineOffset

                var i = rowStart
                while (i < rowEnd) {
                    val run = TerminalRuns.nextBgRun(snapshot, i, rowEnd, selectionPaint)
                    if (run.fill) {
                        bgPaint.color = run.argb
                        nCanvas.drawRect(
                            (i - rowStart) * cellWidth,
                            y,
                            (run.end - rowStart) * cellWidth,
                            y + cellHeight,
                            bgPaint,
                        )
                    }
                    i = run.end
                }

                i = rowStart
                while (i < rowEnd) {
                    val run = TerminalRuns.nextTextRun(snapshot, i, rowEnd, ::paintChoiceAt)
                    if (run.text.isNotEmpty()) {
                        val paint = paintFor(run.paintChoice)
                        paint.color = run.fgArgb
                        paint.isFakeBoldText =
                            (run.styleBits and TerminalSnapshot.CELL_FLAG_BOLD) != 0
                        paint.isUnderlineText =
                            (run.styleBits and TerminalSnapshot.CELL_FLAG_UNDERLINE) != 0
                        paint.textSkewX =
                            if ((run.styleBits and TerminalSnapshot.CELL_FLAG_ITALIC) != 0) {
                                ITALIC_SKEW
                            } else {
                                0f
                            }
                        paint.alpha =
                            if ((run.styleBits and TerminalSnapshot.CELL_FLAG_FAINT) != 0) {
                                FAINT_TEXT_ALPHA
                            } else {
                                255
                            }
                        nCanvas.drawText(run.text, (i - rowStart) * cellWidth, baseline, paint)
                    }
                    i = run.end
                }
            }

            // Kitty images: the origin comes from the cell grid (same cell size
            // as the text, so it lands on it), but the SIZE is libghostty's own
            // scaled dest_w/dest_h — already centred by cell_*_offset. Deriving
            // it from grid*cell instead is how images end up subtly wrong.
            for (img in snapshot.images) {
                val srcRect = Rect(img.srcX, img.srcY, img.srcX + img.srcW, img.srcY + img.srcH)
                val destX = (img.cellCol * cellWidth + img.cellXOffset.toFloat()).toInt()
                val destY = (img.cellRow * cellHeight + img.cellYOffset.toFloat()).toInt()
                val dstRect = RectF(
                    destX.toFloat(),
                    destY.toFloat(),
                    (destX + img.destW).toFloat(),
                    (destY + img.destH).toFloat(),
                )
                nCanvas.drawBitmap(img.bitmap, srcRect, dstRect, null)
            }

            if (snapshot.cursorVisible &&
                blinkOn &&
                snapshot.cursorX in 0 until cols &&
                snapshot.cursorY in 0 until rows
            ) {
                val cursorLeft = snapshot.cursorX * cellWidth
                val cursorTop = snapshot.cursorY * cellHeight
                val draw = TerminalCursorGeometry.forShape(
                    shape = cursorStyle.shape,
                    cellLeft = cursorLeft,
                    cellTop = cursorTop,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    thickness = cursorThinPx,
                )
                cursorPaint.color = cursorColorArgb
                if (draw.stroked) {
                    // A hollow block is drawn inset by half the stroke, or
                    // Android centres the stroke on the path and half of it
                    // lands in the neighbouring cells.
                    val inset = cursorStrokePx / 2f
                    cursorPaint.style = Paint.Style.STROKE
                    cursorPaint.strokeWidth = cursorStrokePx
                    nCanvas.drawRect(
                        draw.left + inset,
                        draw.top + inset,
                        draw.right - inset,
                        draw.bottom - inset,
                        cursorPaint,
                    )
                    cursorPaint.style = Paint.Style.FILL
                } else {
                    nCanvas.drawRect(draw.left, draw.top, draw.right, draw.bottom, cursorPaint)
                }
                // Repaint the covered glyph in the accent color, or a filled
                // block cursor would simply hide the character under it. Only
                // the filled block covers anything, so only it inverts.
                val cursorIndex = snapshot.cursorY * cols + snapshot.cursorX
                val cp = snapshot.codepoints[cursorIndex]
                if (draw.invertGlyph && cp != 0 && cp != 32) {
                    val paint = paintFor(paintChoiceAt(cursorIndex))
                    paint.isFakeBoldText = false
                    paint.isUnderlineText = false
                    paint.textSkewX = 0f
                    paint.alpha = 255
                    paint.color = cursorTextColorArgb
                    nCanvas.drawText(
                        TerminalRuns.glyphAt(snapshot, cursorIndex),
                        cursorLeft,
                        cursorTop + baselineOffset,
                        paint,
                    )
                }
            }
        }
    }
}

private data class ScrollDelta(val delta: Int, val x: Float, val y: Float)

/** Which gesture claimed the pointer. `None` is still undecided. */
private enum class DragMode { None, ClientSelection, AppMouse }

/** The previous tap, for double-tap detection. Mutable and gesture-scoped. */
private class LastTap {
    var timeMs: Long = 0L
    var position: Offset = Offset.Zero
}

/**
 * What a plain tap (no drag, no pinch) does, in priority order:
 * a **second** tap selects the word under it; otherwise a tap with a selection
 * up dismisses it; otherwise it forwards a click and raises the keyboard.
 *
 * Dismiss-before-click is deliberate: with a selection on screen the obvious
 * reading of a tap is "I'm done with that", and sending the app a click as well
 * would move its cursor at the same time.
 */
@Suppress("LongParameterList")
private fun onTapRelease(
    downPosition: Offset,
    tapTime: Long,
    lastTap: LastTap,
    snapshot: TerminalSnapshot?,
    selection: TerminalSelection?,
    cellWidth: Float,
    cellHeight: Float,
    doubleTapTimeoutMs: Long,
    doubleTapSlopPx: Float,
    haptics: HapticFeedback,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onPrimaryClick: (Float, Float) -> Unit,
    onTap: () -> Unit,
) {
    val sinceLast = tapTime - lastTap.timeMs
    val distanceFromLast = hypot(
        (downPosition.x - lastTap.position.x).toDouble(),
        (downPosition.y - lastTap.position.y).toDouble(),
    ).toFloat()
    lastTap.timeMs = tapTime
    lastTap.position = downPosition

    if (sinceLast < doubleTapTimeoutMs && distanceFromLast < doubleTapSlopPx) {
        val cell = snapshot?.let {
            TerminalSelections.cellAt(it, downPosition.x, downPosition.y, cellWidth, cellHeight)
        }
        val word = cell?.let { TerminalSelections.wordAt(snapshot, it) } ?: return
        onSelectionChange(TerminalSelection(word.first, word.last))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        return
    }
    if (selection != null) {
        onSelectionChange(null)
        return
    }
    // Both, in this order: a mouse-tracking app gets the click (the emulator
    // drops it when nothing is tracking), and the keyboard comes up either way.
    onPrimaryClick(downPosition.x, downPosition.y)
    onTap()
}

/**
 * A bundled `res/font` face, or null if it cannot be loaded.
 *
 * `ResourcesCompat.getFont` throws rather than returning null when the resource
 * is missing or unparseable, and a terminal that refuses to draw is worse than
 * one drawing in the wrong face — so the failure is logged and swallowed, and
 * the caller picks the system face it wants to degrade to.
 */
@Composable
private fun rememberFont(@FontRes id: Int): Typeface? {
    val context = LocalContext.current
    return remember(context, id) {
        runCatching { ResourcesCompat.getFont(context, id) }
            .onFailure { Log.w(FONT_TAG, "terminal font $id failed to load", it) }
            .getOrNull()
    }
}

private fun terminalPaint(typeface: Typeface, sizePx: Float): Paint = Paint().apply {
    isAntiAlias = true
    textAlign = Paint.Align.LEFT
    textSize = sizePx
    this.typeface = typeface
}

private const val FONT_TAG = "GhosttyCanvasFont"

const val DEFAULT_TERMINAL_FONT_SIZE_SP: Float = 14f
private const val MIN_FONT_SIZE_SP: Float = 6f
private const val MAX_FONT_SIZE_SP: Float = 32f
private const val FAINT_TEXT_ALPHA: Int = 96
private const val ITALIC_SKEW: Float = -0.25f
/** Below this, a drag is treated as horizontal noise rather than a scroll. */
private const val VERTICAL_INTENT_RATIO: Float = 1.2f
/** A long press tolerates this much more wobble than a scroll's touch slop. */
private const val LONG_PRESS_SLOP_FACTOR: Float = 1.5f

/** Outline width of a hollow-block cursor. */
private const val CURSOR_STROKE_DP: Float = 1.5f

/** Thickness of a bar or underline cursor. */
private const val CURSOR_THIN_DP: Float = 2f

/** Half-period of the cursor blink — 500ms on, 500ms off, as xterm does it. */
private const val CURSOR_BLINK_PHASE_MS: Long = 500L
/** How close to an edge a selection drag must get before the view scrolls. */
private const val AUTO_SCROLL_EDGE_ZONE_DP: Int = 48
/** Auto-scroll cadence — fast enough to feel continuous, slow enough to aim. */
private const val AUTO_SCROLL_INTERVAL_MS: Long = 55L
/** Rows per auto-scroll tick at the very edge. */
private const val MAX_AUTO_SCROLL_ROWS: Int = 8
