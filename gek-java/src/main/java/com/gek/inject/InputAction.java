package com.gek.inject;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single, platform-agnostic input/edit action that can be executed against the current
 * focused text cursor/field (local or, in future, remote) via an {@link InputExecutor}.
 * <p>
 * Designed to be extensible: new action kinds/parameters can be added without changing the
 * voice pipeline, and executors may support them differently per platform.
 */
public final class InputAction {
    public enum Kind {
        /** Type/insert text at caret (replaces current selection in most editors). */
        TYPE_TEXT,
        /** Press backspace N times. */
        BACKSPACE,
        /** Press delete (forward delete) N times. */
        DELETE_FORWARD,
        /** Move caret left/right/up/down without selecting. */
        MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN,
        /** Move caret left/right while selecting (Shift+arrow semantics). */
        SELECT_LEFT, SELECT_RIGHT, SELECT_UP, SELECT_DOWN,
        /** Select word(s) to the left/right of caret (Ctrl+Shift+Arrow semantics, best-effort). */
        SELECT_WORD_LEFT, SELECT_WORD_RIGHT,
        /** Select all content in focused control (Ctrl/Cmd+A, best-effort). */
        SELECT_ALL,
        /** Press Enter / newline. */
        ENTER,
        /** Press Tab. */
        TAB,
        /** Press Escape. */
        ESCAPE,
        /** Undo / Redo (Ctrl/Cmd+Z / Ctrl/Cmd+Y, best-effort). */
        UNDO, REDO,
        /** Copy/Cut/Paste clipboard shortcuts (best-effort). */
        COPY, CUT, PASTE,
        /** Generic key chord, e.g. ctrl+s; executor interprets {@link #text}. */
        KEY_CHORD
    }

    private final Kind kind;
    private final String text;   // for TYPE_TEXT / KEY_CHORD
    private final int count;     // repeat count for movement/delete actions (>=1)

    public InputAction(Kind kind, String text, int count) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = text;
        this.count = Math.max(1, count);
    }

    public static InputAction typeText(String text) { return new InputAction(Kind.TYPE_TEXT, text == null ? "" : text, 1); }
    public static InputAction backspace(int n) { return new InputAction(Kind.BACKSPACE, null, n); }
    public static InputAction deleteForward(int n) { return new InputAction(Kind.DELETE_FORWARD, null, n); }
    public static InputAction moveLeft(int n) { return new InputAction(Kind.MOVE_LEFT, null, n); }
    public static InputAction moveRight(int n) { return new InputAction(Kind.MOVE_RIGHT, null, n); }
    public static InputAction moveUp(int n) { return new InputAction(Kind.MOVE_UP, null, n); }
    public static InputAction moveDown(int n) { return new InputAction(Kind.MOVE_DOWN, null, n); }
    public static InputAction selectLeft(int n) { return new InputAction(Kind.SELECT_LEFT, null, n); }
    public static InputAction selectRight(int n) { return new InputAction(Kind.SELECT_RIGHT, null, n); }
    public static InputAction selectUp(int n) { return new InputAction(Kind.SELECT_UP, null, n); }
    public static InputAction selectDown(int n) { return new InputAction(Kind.SELECT_DOWN, null, n); }
    public static InputAction selectWordLeft(int n) { return new InputAction(Kind.SELECT_WORD_LEFT, null, n); }
    public static InputAction selectWordRight(int n) { return new InputAction(Kind.SELECT_WORD_RIGHT, null, n); }
    public static InputAction selectAll() { return new InputAction(Kind.SELECT_ALL, null, 1); }
    public static InputAction enter() { return new InputAction(Kind.ENTER, null, 1); }
    public static InputAction tab() { return new InputAction(Kind.TAB, null, 1); }
    public static InputAction escape() { return new InputAction(Kind.ESCAPE, null, 1); }
    public static InputAction undo() { return new InputAction(Kind.UNDO, null, 1); }
    public static InputAction redo() { return new InputAction(Kind.REDO, null, 1); }
    public static InputAction copy() { return new InputAction(Kind.COPY, null, 1); }
    public static InputAction cut() { return new InputAction(Kind.CUT, null, 1); }
    public static InputAction paste() { return new InputAction(Kind.PASTE, null, 1); }
    public static InputAction keyChord(String chord) { return new InputAction(Kind.KEY_CHORD, chord == null ? "" : chord, 1); }

    public Kind kind() { return kind; }
    public String text() { return text; }
    public int count() { return count; }

    @Override public String toString() {
        if (kind == Kind.TYPE_TEXT) return "TYPE_TEXT(" + preview(text) + ")";
        if (kind == Kind.KEY_CHORD) return "KEY_CHORD(" + text + ")";
        return kind + (count != 1 ? "x" + count : "");
    }

    private static String preview(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() <= 24 ? t : t.substring(0, 21) + "...";
    }

    public static List<InputAction> none() { return Collections.emptyList(); }
}
