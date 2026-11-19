package com.gek.inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets a spoken transcript into executable {@link InputAction}s for voice typing/editing.
 * <p>
 * Philosophy: enable "type anywhere with a text cursor" and "edit by voice" dynamically.
 * - Plain speech becomes {@code TYPE_TEXT} (insert at caret; replaces any current selection).
 * - Recognized editing/command phrases become navigation/selection/deletion/key actions
 *   (backspace, delete, arrows, select word/all, new line, tab, undo/redo, copy/cut/paste, etc.).
 * <p>
 * Rule-based and ordered; designed to be extended with new matchers/languages or replaced by
 * a strategy/registry without changing the dictation pipeline.
 */
public class VoiceCommandInterpreter {

    public record Interpretation(List<InputAction> actions, boolean command, String description) {}

    public Interpretation interpret(String transcript) {
        if (transcript == null) return new Interpretation(InputAction.none(), false, "empty");
        String raw = transcript.trim();
        if (raw.isEmpty()) return new Interpretation(InputAction.none(), false, "empty");

        String norm = normalize(raw);
        Interpretation cmd = match(norm, raw);
        if (cmd != null) return cmd;

        // Default: type the spoken text (replaces selection at caret in most editors)
        return new Interpretation(List.of(InputAction.typeText(ensureTrailingSpace(raw))), false, "type");
    }

    protected Interpretation match(String norm, String raw) {
        if (equalsAny(norm, "select all", "select everything", "highlight all")) {
            return cmd(List.of(InputAction.selectAll()), "select-all");
        }
        if (equalsAny(norm, "copy", "copy that", "copy selection", "copy selected")) return cmd(List.of(InputAction.copy()), "copy");
        if (equalsAny(norm, "cut", "cut that", "cut selection", "cut selected")) return cmd(List.of(InputAction.cut()), "cut");
        if (equalsAny(norm, "paste", "paste that")) return cmd(List.of(InputAction.paste()), "paste");
        if (equalsAny(norm, "undo", "undo that")) return cmd(List.of(InputAction.undo()), "undo");
        if (equalsAny(norm, "redo", "redo that")) return cmd(List.of(InputAction.redo()), "redo");

        if (equalsAny(norm, "new line", "newline", "line break", "next line", "press enter", "enter", "return")) {
            return cmd(List.of(InputAction.enter()), "enter");
        }
        if (equalsAny(norm, "new paragraph", "paragraph break")) {
            return cmd(List.of(InputAction.enter(), InputAction.enter()), "paragraph");
        }
        if (equalsAny(norm, "tab", "press tab", "tab key", "indent")) {
            return cmd(List.of(InputAction.tab()), "tab");
        }
        if (equalsAny(norm, "escape", "press escape", "cancel")) {
            return cmd(List.of(InputAction.escape()), "escape");
        }

        if (equalsAny(norm, "delete that", "delete selection", "delete selected", "remove that", "remove selection", "remove selected", "clear selection", "clear that", "delete")) {
            return cmd(List.of(InputAction.deleteForward(1)), "delete-selection");
        }
        if (equalsAny(norm, "backspace", "backspace that", "scratch that")) {
            return cmd(List.of(InputAction.backspace(1)), "backspace");
        }

        Interpretation selWords = matchSelectWords(norm);
        if (selWords != null) return selWords;
        Interpretation selChars = matchSelectChars(norm);
        if (selChars != null) return selChars;
        Interpretation del = matchDeleteBackspace(norm);
        if (del != null) return del;
        Interpretation move = matchMove(norm);
        if (move != null) return move;
        Interpretation literal = matchLiteral(raw, norm);
        if (literal != null) return literal;

        return null;
    }

    private Interpretation matchSelectWords(String norm) {
        if (equalsAny(norm, "select word", "select last word", "select previous word", "select this word")) {
            return cmd(List.of(InputAction.selectWordLeft(1)), "select-word-left");
        }
        if (equalsAny(norm, "select next word")) {
            return cmd(List.of(InputAction.selectWordRight(1)), "select-word-right");
        }
        Pattern p = Pattern.compile("^(?:select|highlight)\\s+(?:(last|previous|back)|(next|forward))\\s+(\\w+)\\s+(?:word|words)$");
        Matcher m = p.matcher(norm);
        if (m.find()) {
            boolean left = m.group(1) != null;
            int n = parseCount(m.group(3), 1);
            return cmd(List.of(left ? InputAction.selectWordLeft(n) : InputAction.selectWordRight(n)), "select-words");
        }
        Pattern p2 = Pattern.compile("^(?:select|highlight)\\s+(\\w+)\\s+(?:word|words)\\s+(left|back|previous|right|forward|next)$");
        m = p2.matcher(norm);
        if (m.find()) {
            int n = parseCount(m.group(1), 1);
            String dir = m.group(2);
            boolean left = "left".equals(dir) || "back".equals(dir) || "previous".equals(dir);
            return cmd(List.of(left ? InputAction.selectWordLeft(n) : InputAction.selectWordRight(n)), "select-words");
        }
        return null;
    }

    private Interpretation matchSelectChars(String norm) {
        Pattern left = Pattern.compile("^select\\s+(\\w+)\\s+(?:character|characters|char|chars|letter|letters)\\s+(?:left|back)$");
        Matcher m = left.matcher(norm);
        if (m.find()) return cmd(List.of(InputAction.selectLeft(parseCount(m.group(1), 1))), "select-left");
        Pattern right = Pattern.compile("^select\\s+(\\w+)\\s+(?:character|characters|char|chars|letter|letters)\\s+(?:right|forward)$");
        m = right.matcher(norm);
        if (m.find()) return cmd(List.of(InputAction.selectRight(parseCount(m.group(1), 1))), "select-right");
        return null;
    }

    private Interpretation matchDeleteBackspace(String norm) {
        // "delete last two words", "backspace 3 characters", "remove previous word", "delete 2 lines", "backspace three"
        Pattern p = Pattern.compile("^(delete|backspace|remove|clear|scratch)(?:\\s+(.*))?$");
        Matcher m = p.matcher(norm);
        if (!m.find()) return null;
        String verb = m.group(1);
        String rest = m.group(2) == null ? "" : m.group(2).trim();
        if (rest.isEmpty()) {
            if ("backspace".equals(verb) || "scratch".equals(verb)) return cmd(List.of(InputAction.backspace(1)), "backspace");
            return cmd(List.of(InputAction.deleteForward(1)), "delete");
        }
        String pos = null, dirTok = null, unit = null, numTok = null;
        List<String> tokens = new ArrayList<>(List.of(rest.split("\\s+")));
        // extract direction/position hints
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String t = tokens.get(i);
            if (dirTok == null && (t.equals("left") || t.equals("right") || t.equals("forward") || t.equals("back"))) { dirTok = t; tokens.remove(i); continue; }
            if (pos == null && (t.equals("last") || t.equals("previous") || t.equals("next"))) { pos = t; tokens.remove(i); }
        }
        // extract unit
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String t = tokens.get(i);
            if (isUnit(t)) { unit = t; tokens.remove(i); break; }
        }
        // remaining token as count (number word or digit)
        if (!tokens.isEmpty()) numTok = tokens.get(0);

        int n = parseCount(numTok, 1);
        boolean forward = "delete".equals(verb) || "right".equals(dirTok) || "forward".equals(dirTok) || "next".equals(dirTok) || "next".equals(pos);
        boolean back = "backspace".equals(verb) || "scratch".equals(verb) || "left".equals(dirTok) || "back".equals(dirTok) || "previous".equals(dirTok) || "last".equals(pos) || "previous".equals(pos);
        if (!forward && !back) back = !"delete".equals(verb);

        if (unit != null && unit.startsWith("word")) {
            List<InputAction> actions = new ArrayList<>();
            actions.add(back ? InputAction.selectWordLeft(n) : InputAction.selectWordRight(n));
            actions.add(InputAction.deleteForward(1));
            return cmd(actions, back ? "delete-words-left" : "delete-words-right");
        }
        if (unit != null && unit.startsWith("line")) {
            List<InputAction> actions = new ArrayList<>();
            for (int i = 0; i < n; i++) actions.add(InputAction.selectUp(1));
            actions.add(InputAction.deleteForward(1));
            return cmd(actions, "delete-lines");
        }
        return cmd(List.of(back ? InputAction.backspace(n) : InputAction.deleteForward(n)), back ? "backspace-n" : "delete-n");
    }

    private static boolean isUnit(String t) {
        return t.equals("word") || t.equals("words") || t.equals("character") || t.equals("characters")
                || t.equals("char") || t.equals("chars") || t.equals("letter") || t.equals("letters")
                || t.equals("line") || t.equals("lines");
    }

    private Interpretation matchMove(String norm) {
        Pattern p = Pattern.compile("^(?:move|go|cursor|press)\\s+(left|right|up|down)(?:\\s+arrow)?(?:\\s+(\\w+))?(?:\\s+(?:times|time|steps|step|chars|characters))?$");
        Matcher m = p.matcher(norm);
        if (m.find()) {
            String dir = m.group(1);
            int n = parseCount(m.group(2), 1);
            return switch (dir) {
                case "left" -> cmd(List.of(InputAction.moveLeft(n)), "move-left");
                case "right" -> cmd(List.of(InputAction.moveRight(n)), "move-right");
                case "up" -> cmd(List.of(InputAction.moveUp(n)), "move-up");
                case "down" -> cmd(List.of(InputAction.moveDown(n)), "move-down");
                default -> null;
            };
        }
        Pattern p2 = Pattern.compile("^(left|right|up|down)\\s+arrow(?:\\s+(\\w+))?(?:\\s+(?:times|time))?$");
        m = p2.matcher(norm);
        if (m.find()) {
            String dir = m.group(1);
            int n = parseCount(m.group(2), 1);
            return switch (dir) {
                case "left" -> cmd(List.of(InputAction.moveLeft(n)), "move-left");
                case "right" -> cmd(List.of(InputAction.moveRight(n)), "move-right");
                case "up" -> cmd(List.of(InputAction.moveUp(n)), "move-up");
                case "down" -> cmd(List.of(InputAction.moveDown(n)), "move-down");
                default -> null;
            };
        }
        return null;
    }

    private Interpretation matchLiteral(String raw, String norm) {
        for (String prefix : new String[]{"type ", "literal ", "dictate ", "insert "}) {
            if (norm.startsWith(prefix)) {
                String text = raw.substring(prefix.length()).trim();
                if (!text.isEmpty()) return new Interpretation(List.of(InputAction.typeText(ensureTrailingSpace(text))), false, "type-literal");
            }
        }
        return null;
    }

    private static Interpretation cmd(List<InputAction> actions, String desc) {
        return new Interpretation(actions, true, desc);
    }

    private static boolean equalsAny(String norm, String... options) {
        for (String o : options) if (norm.equals(o)) return true;
        return false;
    }

    private static String normalize(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim();
        t = t.replaceAll("[.,!?;:]+$", "");
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    private static int parseCount(String token, int def) {
        if (token == null || token.isBlank()) return def;
        String t = token.toLowerCase(Locale.ROOT).trim();
        try { return Math.max(1, Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
        return switch (t) {
            case "one", "a", "an", "once" -> 1;
            case "two", "twice", "couple" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            default -> def;
        };
    }

    private static String ensureTrailingSpace(String text) {
        if (text == null || text.isEmpty()) return text;
        char last = text.charAt(text.length() - 1);
        if (Character.isWhitespace(last)) return text;
        return text + " ";
    }
}
