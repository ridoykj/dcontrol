package com.dcontrol.client.feature.remotecontrol

import com.dcontrol.client.feature.remotecontrol.domain.EditableNode
import com.dcontrol.client.feature.remotecontrol.domain.TextInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInjectorTest {

    private val injector = TextInjector()

    private class FakeNode(
        override var text: CharSequence? = "",
        override var selectionStart: Int = -1,
        override var selectionEnd: Int = -1,
        override var showingHint: Boolean = false,
    ) : EditableNode {
        var enterPressed = false
        override fun setText(value: CharSequence): Boolean {
            text = value
            showingHint = false
            return true
        }
        override fun imeEnter(): Boolean {
            enterPressed = true
            return true
        }
    }

    @Test
    fun `insert at cursor splices the text`() {
        val node = FakeNode(text = "helloworld", selectionStart = 5, selectionEnd = 5)
        assertTrue(injector.insert(node, " "))
        assertEquals("hello world", node.text)
    }

    @Test
    fun `an empty field showing its hint is typed into, not appended to`() {
        val node = FakeNode(text = "Search…", selectionStart = 0, selectionEnd = 0, showingHint = true)
        injector.insert(node, "d")
        assertEquals("d", node.text)
        // ACTION_SET_TEXT leaves the cursor at the end of the new text.
        node.selectionStart = 1
        node.selectionEnd = 1
        injector.insert(node, "c")
        assertEquals("dc", node.text)

        val hinted = FakeNode(text = "Search…", showingHint = true)
        assertFalse("nothing to delete in an empty field", injector.backspace(hinted))
    }

    @Test
    fun `insert with no selection appends at the end`() {
        val node = FakeNode(text = "abc")
        injector.insert(node, "d")
        assertEquals("abcd", node.text)
    }

    @Test
    fun `insert replaces the current selection`() {
        val node = FakeNode(text = "abXYcd", selectionStart = 2, selectionEnd = 4)
        injector.insert(node, "--")
        assertEquals("ab--cd", node.text)
    }

    @Test
    fun `empty insert is a no-op`() {
        val node = FakeNode(text = "abc")
        assertFalse(injector.insert(node, ""))
        assertEquals("abc", node.text)
    }

    @Test
    fun `backspace deletes the char before the cursor`() {
        val node = FakeNode(text = "abc", selectionStart = 3, selectionEnd = 3)
        injector.backspace(node)
        assertEquals("ab", node.text)
    }

    @Test
    fun `backspace on an empty field does nothing`() {
        val node = FakeNode(text = "", selectionStart = 0, selectionEnd = 0)
        assertFalse(injector.backspace(node))
    }

    @Test
    fun `enter delegates to the ime action`() {
        val node = FakeNode(text = "x")
        assertTrue(injector.enter(node))
        assertTrue(node.enterPressed)
    }
}
