package com.gek.inject;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoiceCommandInterpreterCoverageTest {
    private final VoiceCommandInterpreter i = new VoiceCommandInterpreter();

    @Test void moreMoveAndArrowForms() {
        assertEquals(InputAction.Kind.MOVE_LEFT, i.interpret("cursor left").actions().get(0).kind());
        assertEquals(InputAction.Kind.MOVE_RIGHT, i.interpret("press right").actions().get(0).kind());
        assertEquals(InputAction.Kind.MOVE_UP, i.interpret("go up").actions().get(0).kind());
        assertEquals(2, i.interpret("go up twice").actions().get(0).count());
        assertEquals(4, i.interpret("move down four characters").actions().get(0).count());
        assertEquals(1, i.interpret("left arrow once").actions().get(0).count());
        assertEquals(2, i.interpret("up arrow 2 times").actions().get(0).count());
        assertEquals(InputAction.Kind.MOVE_DOWN, i.interpret("down arrow").actions().get(0).kind());
    }

    @Test void moreSelectAndDeleteForms() {
        // select words right/left phrasing variants
        var sw = i.interpret("select 3 words left");
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, sw.actions().get(0).kind());
        assertEquals(3, sw.actions().get(0).count());
        var sw2 = i.interpret("highlight 2 words forward");
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, sw2.actions().get(0).kind());
        assertEquals(2, sw2.actions().get(0).count());

        var sc = i.interpret("select one character right");
        assertEquals(InputAction.Kind.SELECT_RIGHT, sc.actions().get(0).kind());
        assertEquals(1, sc.actions().get(0).count());

        // delete/backspace variants
        assertEquals("backspace", i.interpret("backspace that").description());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("delete selected").actions().get(0).kind());
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("remove selected").actions().get(0).kind());

        var dlw = i.interpret("delete last word");
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, dlw.actions().get(0).kind());
        var drw = i.interpret("remove next word");
        assertEquals(InputAction.Kind.SELECT_WORD_RIGHT, drw.actions().get(0).kind());
        var clearPrev = i.interpret("clear previous 2 words");
        assertEquals(InputAction.Kind.SELECT_WORD_LEFT, clearPrev.actions().get(0).kind());
        assertEquals(2, clearPrev.actions().get(0).count());

        var back2 = i.interpret("backspace previous 2 characters");
        assertEquals(InputAction.Kind.BACKSPACE, back2.actions().get(0).kind());
        assertEquals(2, back2.actions().get(0).count());

        var delNext = i.interpret("delete next 1 line");
        assertEquals("delete-lines", delNext.description());

        // bare delete/remove/clear -> delete forward, bare backspace/scratch -> backspace
        assertEquals(InputAction.Kind.DELETE_FORWARD, i.interpret("delete").actions().get(0).kind());
        assertEquals(InputAction.Kind.BACKSPACE, i.interpret("backspace").actions().get(0).kind());
        assertEquals(InputAction.Kind.BACKSPACE, i.interpret("scratch").actions().get(0).kind());
    }

    @Test void literalAndFallbackEdges() {
        // empty literal prefix falls through to typing the whole phrase
        var r = i.interpret("type ");
        assertFalse(r.command());
        assertEquals(InputAction.Kind.TYPE_TEXT, r.actions().get(0).kind());
        var r2 = i.interpret("literal");
        assertFalse(r2.command());

        // unknown command-like text types out
        var u = i.interpret("please save file");
        assertFalse(u.command());
        assertEquals(InputAction.Kind.TYPE_TEXT, u.actions().get(0).kind());

        // punctuation/case normalization
        assertEquals(InputAction.Kind.COPY, i.interpret("COPY THAT!").actions().get(0).kind());
        assertEquals(InputAction.Kind.SELECT_ALL, i.interpret("Select All.").actions().get(0).kind());
    }

    @Test void newlineAndParagraphVariants() {
        assertEquals(InputAction.Kind.ENTER, i.interpret("press enter").actions().get(0).kind());
        assertEquals(InputAction.Kind.ENTER, i.interpret("line break").actions().get(0).kind());
        assertEquals(InputAction.Kind.ENTER, i.interpret("next line").actions().get(0).kind());
        List<InputAction> para = i.interpret("paragraph break").actions();
        assertEquals(2, para.size());
        assertEquals(InputAction.Kind.ENTER, para.get(0).kind());
        assertEquals(InputAction.Kind.ENTER, para.get(1).kind());
        assertEquals(InputAction.Kind.TAB, i.interpret("tab key").actions().get(0).kind());
    }
}
