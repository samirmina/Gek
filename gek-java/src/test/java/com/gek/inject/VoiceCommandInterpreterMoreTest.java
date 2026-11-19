package com.gek.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoiceCommandInterpreterMoreTest {
    private final VoiceCommandInterpreter i = new VoiceCommandInterpreter();

    @Test void parseCountAndMoveVariants() {
        assertEquals(1, i.interpret("move left once").actions().get(0).count());
        assertEquals(2, i.interpret("move right twice").actions().get(0).count());
        assertEquals(2, i.interpret("cursor up twice").actions().get(0).count());
        assertEquals(10, i.interpret("press down ten").actions().get(0).count());
        assertEquals(5, i.interpret("go left 5 times").actions().get(0).count());
        assertEquals(InputAction.Kind.MOVE_UP, i.interpret("up arrow 3 times").actions().get(0).kind());
        assertEquals(3, i.interpret("down arrow three times").actions().get(0).count());
        assertEquals(InputAction.Kind.MOVE_LEFT, i.interpret("left arrow").actions().get(0).kind());
        assertEquals(InputAction.Kind.MOVE_RIGHT, i.interpret("right arrow twice").actions().get(0).kind());
        assertEquals(1, i.interpret("move left xyz").actions().get(0).count());
    }

    @Test void selectionAndEditVariants() {
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, i.interpret("select previous word").actions().get(0).kind());
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, i.interpret("select this word").actions().get(0).kind());
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, i.interpret("highlight last 1 word").actions().get(0).kind());
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, i.interpret("highlight next four words").actions().get(0).kind());
        assertEquals(InputAction.Kind.SELECT_ALL, i.interpret("highlight all").actions().get(0).kind());
        assertEquals(InputAction.Kind.SELECT_ALL, i.interpret("select everything").actions().get(0).kind());

        var delSel = i.interpret("clear selection");
        assertEquals(InputAction.Kind.DELETE_FORWARD, delSel.actions().get(0).kind());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("remove that").actions().get(0).kind());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("clear that").actions().get(0).kind());
        assertEquals(InputAction.Kind.BACKSPACE, i.interpret("scratch that").actions().get(0).kind());

        var bsWord = i.interpret("remove previous word");
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, bsWord.actions().get(0).kind());
        assertEquals(InputAction.Kind.DELETE_FORWARD, bsWord.actions().get(1).kind());

        var delChars = i.interpret("delete 3 characters forward");
        assertEquals(InputAction.Kind.DELETE_FORWARD, delChars.actions().get(0).kind());
        assertEquals(3, delChars.actions().get(0).count());

        var scratch2 = i.interpret("scratch two characters");
        assertEquals(InputAction.Kind.BACKSPACE, scratch2.actions().get(0).kind());
        assertEquals(2, scratch2.actions().get(0).count());

        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("clear next 2 chars").actions().get(0).kind());
    }

    @Test void literalPrefixesAndWhitespaceTyping() {
        VoiceCommandInterpreter.Interpretation a = i.interpret("dictate select all");
        assertFalse(a.command());
        assertTrue(a.actions().get(0).text().contains("select all"));
        VoiceCommandInterpreter.Interpretation b = i.interpret("insert undo");
        assertFalse(b.command());
        assertTrue(b.actions().get(0).text().contains("undo"));

        // spoken text that already ends with whitespace should not gain extra space
        VoiceCommandInterpreter.Interpretation c = i.interpret("hello \n");
        assertEquals(InputAction.Kind.TYPE_TEXT, c.actions().get(0).kind());
        String t = c.actions().get(0).text();
        assertTrue(t.endsWith("\n") || t.endsWith(" \n") || t.endsWith(" "));

        // "delete <unrecognized>" becomes a delete command (no unit/direction match -> delete/backspace by verb)
        VoiceCommandInterpreter.Interpretation d = i.interpret("delete the dragon");
        assertTrue(d.command());
        assertEquals(InputAction.Kind.DELETE_FORWARD, d.actions().get(0).kind());
    }

    @Test void commandDescriptionsAndShortcuts() {
        assertEquals("copy", i.interpret("copy selection").description());
        assertEquals("cut", i.interpret("cut selected").description());
        assertEquals("paste", i.interpret("paste that").description());
        assertEquals("enter", i.interpret("return").description());
        assertEquals("tab", i.interpret("indent").description());
        assertEquals("escape", i.interpret("cancel").description());
        assertEquals("select-all", i.interpret("select everything").description());
    }
}
