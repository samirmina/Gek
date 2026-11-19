package com.gek.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoiceCommandInterpreterTest {
    private final VoiceCommandInterpreter i = new VoiceCommandInterpreter();

    @Test void plainSpeechBecomesTyping() {
        var r = i.interpret("hello world");
        assertFalse(r.command());
        assertEquals(1, r.actions().size());
        assertEquals(InputAction.Kind.TYPE_TEXT, r.actions().get(0).kind());
        assertTrue(r.actions().get(0).text().startsWith("hello world"));
    }

    @Test void emptyOrNullIsNoop() {
        assertTrue(i.interpret(null).actions().isEmpty());
        assertTrue(i.interpret("   ").actions().isEmpty());
        assertFalse(i.interpret("").command());
    }

    @Test void editingCommands() {
        assertTrue(i.interpret("backspace").command());
        assertEquals(InputAction.Kind.BACKSPACE, i.interpret("backspace").actions().get(0).kind());
        assertEquals(3, i.interpret("backspace three").actions().get(0).count());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("delete").actions().get(0).kind());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("delete that").actions().get(0).kind());
        assertEquals(InputAction.Kind.BACKSPACE, i.interpret("scratch that").actions().get(0).kind());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("remove selection").actions().get(0).kind());
    }

    @Test void navigationAndSelection() {
        var left = i.interpret("move left 2");
        assertTrue(left.command());
        assertEquals(InputAction.Kind.MOVE_LEFT, left.actions().get(0).kind());
        assertEquals(2, left.actions().get(0).count());

        var sel = i.interpret("select last word");
        assertTrue(sel.command());
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, sel.actions().get(0).kind());

        var all = i.interpret("select all");
        assertEquals(InputAction.Kind.SELECT_ALL, all.actions().get(0).kind());

        assertEquals(InputAction.Kind.MOVE_RIGHT, i.interpret("right arrow").actions().get(0).kind());
        assertEquals(InputAction.Kind.MOVE_DOWN, i.interpret("go down 3").actions().get(0).kind());
        assertEquals(3, i.interpret("go down 3").actions().get(0).count());
    }

    @Test void selectWordVariants() {
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, i.interpret("select next word").actions().get(0).kind());
        var r = i.interpret("select last 2 words");
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, r.actions().get(0).kind());
        assertEquals(2, r.actions().get(0).count());
        var r2 = i.interpret("select two words right");
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, r2.actions().get(0).kind());
        assertEquals(2, r2.actions().get(0).count());
    }

    @Test void selectCharacters() {
        var l = i.interpret("select 3 characters left");
        assertEquals(InputAction.Kind.SELECT_LEFT, l.actions().get(0).kind());
        assertEquals(3, l.actions().get(0).count());
        var r = i.interpret("select two letters right");
        assertEquals(InputAction.Kind.SELECT_RIGHT, r.actions().get(0).kind());
        assertEquals(2, r.actions().get(0).count());
    }

    @Test void newLineTabUndoRedoClipboard() {
        assertEquals(InputAction.Kind.ENTER, i.interpret("new line").actions().get(0).kind());
        assertEquals(2, i.interpret("new paragraph").actions().size());
        assertEquals(InputAction.Kind.TAB, i.interpret("press tab").actions().get(0).kind());
        assertEquals(InputAction.Kind.ESCAPE, i.interpret("escape").actions().get(0).kind());
        assertEquals(InputAction.Kind.UNDO, i.interpret("undo").actions().get(0).kind());
        assertEquals(InputAction.Kind.REDO, i.interpret("redo that").actions().get(0).kind());
        assertEquals(InputAction.Kind.COPY, i.interpret("copy that").actions().get(0).kind());
        assertEquals(InputAction.Kind.CUT, i.interpret("cut selection").actions().get(0).kind());
        assertEquals(InputAction.Kind.PASTE, i.interpret("paste").actions().get(0).kind());
    }

    @Test void deleteWordsSelectsThenDeletes() {
        var r = i.interpret("delete last two words");
        assertTrue(r.command());
        assertEquals(2, r.actions().size());
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, r.actions().get(0).kind());
        assertEquals(2, r.actions().get(0).count());
        assertEquals(InputAction.Kind.DELETE_FORWARD, r.actions().get(1).kind());

        var fwd = i.interpret("delete next 2 words");
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, fwd.actions().get(0).kind());
    }

    @Test void deleteLinesBackspaceN() {
        var d = i.interpret("delete 2 lines");
        assertTrue(d.command());
        assertEquals("delete-lines", d.description());
        assertEquals(InputAction.Kind.BACKSPACE, i.interpret("backspace 4 characters").actions().get(0).kind());
        assertEquals(4, i.interpret("backspace 4 characters").actions().get(0).count());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("delete 3 characters right").actions().get(0).kind());
    }

    @Test void literalForcesTyping() {
        var r = i.interpret("type select all");
        assertFalse(r.command());
        assertEquals(InputAction.Kind.TYPE_TEXT, r.actions().get(0).kind());
        assertTrue(r.actions().get(0).text().contains("select all"));
        var lit = i.interpret("literal new line");
        assertFalse(lit.command());
        assertTrue(lit.actions().get(0).text().contains("new line"));
    }

    @Test void punctuationTrimmedForCommandMatch() {
        assertEquals(InputAction.Kind.ENTER, i.interpret("new line.").actions().get(0).kind());
        assertEquals(InputAction.Kind.UNDO, i.interpret("undo!").actions().get(0).kind());
    }
}
