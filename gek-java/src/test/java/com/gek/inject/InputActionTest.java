package com.gek.inject;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InputActionTest {
    @Test void factoriesAndAccessors() {
        InputAction t = InputAction.typeText("hi");
        assertEquals(InputAction.Kind.TYPE_TEXT, t.kind());
        assertEquals("hi", t.text());
        assertEquals(1, t.count());

        assertEquals(3, InputAction.backspace(3).count());
        assertEquals(InputAction.Kind.BACKSPACE, InputAction.backspace(0).kind());
        assertEquals(1, InputAction.backspace(0).count(), "count floored to 1");

        assertEquals(InputAction.Kind.DELETE_FORWARD, InputAction.deleteForward(2).kind());
        assertEquals(InputAction.Kind.MOVE_LEFT, InputAction.moveLeft(1).kind());
        assertEquals(InputAction.Kind.MOVE_RIGHT, InputAction.moveRight(1).kind());
        assertEquals(InputAction.Kind.MOVE_UP, InputAction.moveUp(1).kind());
        assertEquals(InputAction.Kind.MOVE_DOWN, InputAction.moveDown(1).kind());
        assertEquals(InputAction.Kind.SELECT_LEFT, InputAction.selectLeft(1).kind());
        assertEquals(InputAction.Kind.SELECT_RIGHT, InputAction.selectRight(1).kind());
        assertEquals(InputAction.Kind.SELECT_UP, InputAction.selectUp(1).kind());
        assertEquals(InputAction.Kind.SELECT_DOWN, InputAction.selectDown(1).kind());
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, InputAction.selectWordLeft(2).kind());
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, InputAction.selectWordRight(2).kind());
        assertEquals(InputAction.Kind.SELECT_ALL, InputAction.selectAll().kind());
        assertEquals(InputAction.Kind.ENTER, InputAction.enter().kind());
        assertEquals(InputAction.Kind.TAB, InputAction.tab().kind());
        assertEquals(InputAction.Kind.ESCAPE, InputAction.escape().kind());
        assertEquals(InputAction.Kind.UNDO, InputAction.undo().kind());
        assertEquals(InputAction.Kind.REDO, InputAction.redo().kind());
        assertEquals(InputAction.Kind.COPY, InputAction.copy().kind());
        assertEquals(InputAction.Kind.CUT, InputAction.cut().kind());
        assertEquals(InputAction.Kind.PASTE, InputAction.paste().kind());

        InputAction chord = InputAction.keyChord("ctrl+s");
        assertEquals(InputAction.Kind.KEY_CHORD, chord.kind());
        assertEquals("ctrl+s", chord.text());
        assertEquals("", InputAction.keyChord(null).text());
        assertEquals("", InputAction.typeText(null).text());
    }

    @Test void toStringPreviews() {
        assertTrue(InputAction.typeText("short").toString().contains("short"));
        assertTrue(InputAction.typeText("012345678901234567890123456789").toString().contains("..."));
        assertTrue(InputAction.backspace(2).toString().contains("x2"));
        assertTrue(InputAction.enter().toString().contains("ENTER"));
        assertEquals("KEY_CHORD(ctrl+z)", InputAction.keyChord("ctrl+z").toString());
    }

    @Test void noneIsEmpty() {
        List<InputAction> n = InputAction.none();
        assertTrue(n.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> n.add(InputAction.enter()));
    }
}
