/*
DrawAnywhere: An Android application that lets you draw on top of other apps.
Copyright (C) 2025-2026 shezik

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free Software
Foundation, either version 3 of the License, or any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.shezik.drawanywhere

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.shezik.drawanywhere.drawing.StrokeTool
import com.shezik.drawanywhere.drawing.ToolContext
import com.shezik.drawanywhere.model.DrawAction
import com.shezik.drawanywhere.model.Stroke
import com.shezik.drawanywhere.model.PenConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the stroke list and undo/redo stack. Drawing logic is delegated to
 * [StrokeTool] implementations via [PenType.createTool].
 */
class DrawController(initialConfig: PenConfig) {
    var penConfig: PenConfig = initialConfig
        private set

    fun setPenConfig(config: PenConfig) {
        penConfig = config
    }

    var onStrokesChanged: (() -> Unit)? = null

    private val _strokeList = mutableListOf<Stroke>()
    val strokeList: List<Stroke>
        get() = _strokeList

    private val undoRedo = UndoRedoManager()
    val canUndo: StateFlow<Boolean> = undoRedo.canUndo
    val canRedo: StateFlow<Boolean> = undoRedo.canRedo

    private val _canClear = MutableStateFlow(false)
    val canClearStrokes: StateFlow<Boolean> = _canClear.asStateFlow()

    private var activeTool: StrokeTool? = null

    private val toolContext get() = ToolContext(
        strokes = _strokeList,
        penConfig = penConfig,
        onUndoPush = undoRedo::push,
        onChanged = ::notifyChanged,
    )

    private fun notifyChanged() {
        _canClear.value = _strokeList.isNotEmpty()
        onStrokesChanged?.invoke()
    }

    fun createStroke(newPoint: Offset) {
        val tool = penConfig.penType.createTool(toolContext)
        activeTool = tool
        tool.onStart(newPoint)
    }

    fun updateLatestStroke(newPoint: Offset) {
        activeTool?.onMove(newPoint)
    }

    fun finishStroke() {
        activeTool?.onFinish()
        activeTool = null
    }

    fun clearStrokes() {
        if (_strokeList.isEmpty()) return
        undoRedo.push(DrawAction.ClearStrokes(_strokeList.toList()))
        _strokeList.clear()
        notifyChanged()
    }

    fun removeExpiredStrokes(now: Long) {
        if (
            _strokeList.removeAll { stroke ->
                val ttl = stroke.penType.ttlMs ?: return@removeAll false
                now - stroke.modifiedAt > ttl
            }
        ) notifyChanged()
    }

    fun undo() {
        val action = undoRedo.popUndo() ?: return
        when (action) {
            is DrawAction.AddStroke -> {
                if (_strokeList.remove(action.stroke)) undoRedo.pushRedo(action)
            }
            is DrawAction.EraseStroke -> {
                _strokeList.add(action.stroke)
                undoRedo.pushRedo(action)
            }
            is DrawAction.ClearStrokes -> {
                _strokeList.addAll(action.strokes)
                undoRedo.pushRedo(action)
            }
            is DrawAction.CanvasSnapshot -> {
                _strokeList.clear()
                _strokeList.addAll(action.before)
                undoRedo.pushRedo(action)
            }
        }
        notifyChanged()
    }

    fun redo() {
        val action = undoRedo.popRedo() ?: return
        when (action) {
            is DrawAction.AddStroke -> {
                _strokeList.add(action.stroke)
                undoRedo.push(action, clearRedo = false)
            }
            is DrawAction.EraseStroke -> {
                if (_strokeList.remove(action.stroke)) undoRedo.push(action, clearRedo = false)
            }
            is DrawAction.ClearStrokes -> {
                _strokeList.removeAll(action.strokes)
                undoRedo.push(action, clearRedo = false)
            }
            is DrawAction.CanvasSnapshot -> {
                _strokeList.clear()
                _strokeList.addAll(action.after)
                undoRedo.push(action, clearRedo = false)
            }
        }
        notifyChanged()
    }

    // ── Export ──────────────────────────────────────────────────

    companion object {
        /** Longest edge of an exported bitmap, to guard against OOM on huge infinite-canvas drawings. */
        private const val MAX_EXPORT_DIMENSION = 4096
        private const val EXPORT_PADDING_PX = 32f
    }

    /**
     * Bounding box (in canvas/world space) of every saveable stroke, expanded by each
     * stroke's own width so thick strokes aren't clipped at their edges. Ephemeral
     * strokes (e.g. the laser pointer) are excluded — they're a transient overlay,
     * not drawn content. Returns null if there's nothing to export.
     */
    fun computeContentBounds(): RectF? {
        val saveable = _strokeList.filterNot { it.penType.isEphemeral }
        if (saveable.isEmpty()) return null

        var bounds: RectF? = null
        for (stroke in saveable) {
            if (stroke.points.isEmpty()) continue
            val halfWidth = stroke.width / 2f
            for (point in stroke.points) {
                val strokeBounds = RectF(
                    point.x - halfWidth, point.y - halfWidth,
                    point.x + halfWidth, point.y + halfWidth
                )
                if (bounds == null) bounds = strokeBounds else bounds.union(strokeBounds)
            }
        }
        return bounds
    }

    /**
     * Renders all saveable strokes to an offscreen [Bitmap] at 1:1 canvas scale
     * (viewport zoom/pan don't apply — strokes are stored in world space already),
     * cropped and padded to their bounding box. Downscales proportionally if the
     * content would exceed [MAX_EXPORT_DIMENSION] in either dimension.
     *
     * @return null if there is nothing to export.
     */
    fun renderToBitmap(padding: Float = EXPORT_PADDING_PX): Bitmap? {
        val bounds = computeContentBounds() ?: return null

        val rawWidth = bounds.width() + padding * 2
        val rawHeight = bounds.height() + padding * 2
        if (rawWidth <= 0f || rawHeight <= 0f) return null

        val scale = min(
            1f,
            MAX_EXPORT_DIMENSION / maxOf(rawWidth, rawHeight)
        )

        val width = (rawWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (rawHeight * scale).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        canvas.scale(scale, scale)
        canvas.translate(padding - bounds.left, padding - bounds.top)

        for (stroke in _strokeList) {
            if (stroke.penType.isEphemeral) continue
            stroke.render(canvas, paint)
        }

        return bitmap
    }
}
