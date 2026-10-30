package com.dcontrol.client.feature.remotecontrol.domain

/**
 * The focused editable field, behind a seam so [TextInjector] can be tested with a fake node instead of a
 * live `AccessibilityNodeInfo`. [selectionStart]/[selectionEnd] are -1 when the field reports no cursor, in
 * which case edits apply at the end of the text.
 */
interface EditableNode {
    val text: CharSequence?
    val selectionStart: Int
    val selectionEnd: Int

    /** True while an empty field reports its hint (for example "Search…") as [text]. */
    val showingHint: Boolean get() = false

    /** Replaces the whole text (`ACTION_SET_TEXT`); returns false when the action is refused. */
    fun setText(value: CharSequence): Boolean

    /** Presses the IME action for this field (`ACTION_IME_ENTER`, API 30+); returns false when unavailable. */
    fun imeEnter(): Boolean
}

/**
 * Inserts text and applies Backspace/Enter on the focused field. Typing is `ACTION_SET_TEXT`, not raw key
 * events, so IME-only and custom-drawn editors may ignore it (documented limitation).
 */
class TextInjector {

    fun insert(node: EditableNode, value: String): Boolean {
        if (value.isEmpty()) return false
        val current = currentText(node)
        val (start, end) = range(node, current)
        val next = current.substring(0, start) + value + current.substring(end)
        return node.setText(next)
    }

    fun backspace(node: EditableNode): Boolean {
        val current = currentText(node)
        val (start, end) = range(node, current)
        val from = if (start == end) (start - 1).coerceAtLeast(0) else start
        if (from == end) return false
        val next = current.substring(0, from) + current.substring(end)
        return node.setText(next)
    }

    fun enter(node: EditableNode): Boolean = node.imeEnter()

    /** The field's real text: an empty field showing its hint has none. */
    private fun currentText(node: EditableNode): String = if (node.showingHint) "" else node.text?.toString() ?: ""

    /** The selection clamped to the text, defaulting to the end when the field reports none. */
    private fun range(node: EditableNode, current: String): Pair<Int, Int> {
        val length = current.length
        val rawStart = node.selectionStart
        val rawEnd = node.selectionEnd
        val start = if (rawStart < 0) length else rawStart.coerceIn(0, length)
        val end = if (rawEnd < 0) length else rawEnd.coerceIn(0, length)
        return if (start <= end) start to end else end to start
    }
}
