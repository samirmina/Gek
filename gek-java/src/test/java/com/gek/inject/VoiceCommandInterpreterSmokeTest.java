package com.gek.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Smoke/calibration for voice command interpretation (portable, no OS injection). */
class VoiceCommandInterpreterSmokeTest {
    private final VoiceCommandInterpreter vci = new VoiceCommandInterpreter();

    @Test
    void plainTextAndEmptyAreNotCommands() {
        for (String text : new String[]{"hello world", "write this down", "new paragraph of text"}) {
            VoiceCommandInterpreter.Interpretation r = vci.interpret(text);
            assertNotNull(r);
            assertFalse(r.command(), "should not be command: " + text);
            assertNotNull(r.actions());
            assertFalse(r.actions().isEmpty(), "plain text should yield type action: " + text);
        }
        VoiceCommandInterpreter.Interpretation empty = vci.interpret("");
        assertNotNull(empty);
        assertFalse(empty.command());
        VoiceCommandInterpreter.Interpretation nul = vci.interpret(null);
        assertNotNull(nul);
        assertFalse(nul.command());
    }

    @Test
    void canonicalEditingCommandsProduceActions() {
        assertCommandHasActions("backspace");
        assertCommandHasActions("delete that");
        assertCommandHasActions("press enter");
        assertCommandHasActions("new line");
        assertCommandHasActions("select all");
        assertCommandHasActions("undo");
        assertCommandHasActions("redo");
        assertCommandHasActions("tab");
        assertCommandHasActions("copy");
        assertCommandHasActions("paste");
    }

    @Test
    void navigationCommandsProduceActions_characterization() {
        // pinned to phrases supported by current VoiceCommandInterpreter.matchMove/arrow forms
        assertCommandHasActions("move left");
        assertCommandHasActions("move right");
        assertCommandHasActions("move up");
        assertCommandHasActions("move down");
        assertCommandHasActions("cursor left");
        assertCommandHasActions("go right");
        assertCommandHasActions("left arrow");
        assertCommandHasActions("right arrow 3 times");
        assertCommandHasActions("up arrow");
        assertCommandHasActions("down arrow");
    }

    private void assertCommandHasActions(String text) {
        VoiceCommandInterpreter.Interpretation r = vci.interpret(text);
        assertTrue(r.command(), "expected command: " + text);
        assertNotNull(r.actions(), "actions null for: " + text);
        assertFalse(r.actions().isEmpty(), "no actions for: " + text);
        assertNotNull(r.description());
        assertFalse(r.description().isBlank());
    }
}
