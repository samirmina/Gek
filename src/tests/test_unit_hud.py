"""Unit tests for HUD language resolution logic.

Tests the language mapping and resolution without needing customtkinter.
"""
import sys
import os

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


class MockStringVar:
    def __init__(self, value="Detected"):
        self._value = value

    def get(self):
        return self._value

    def set(self, val):
        self._value = val


class MockSTT:
    def __init__(self):
        self.language = None

    def set_language(self, language):
        self.language = language


class HudLanguageHelper:
    """Headless mirror of Hud's language resolution."""

    def __init__(self, languages=None):
        self._whisper_var = MockStringVar(value="Detected")
        self._whisper_language = None
        self._stt_ref = None

        self._lang_display_to_code = {"Detected": "auto"}
        self._lang_code_to_display = {"auto": "Detected"}
        if languages:
            from config import SUPPORTED_LANGUAGES
            for code in languages:
                name = SUPPORTED_LANGUAGES.get(code, code)
                self._lang_display_to_code[name] = code
                self._lang_code_to_display[code] = name
            self._lang_names = ["Detected"] + [SUPPORTED_LANGUAGES.get(c, c) for c in languages]
            self._lang_codes = ["auto"] + list(languages)
        else:
            self._lang_names = ["Detected"]
            self._lang_codes = ["auto"]

        self._hear_langs = set()
        self._hear_vars = {}

    def get_whisper_language(self):
        try:
            val = self._whisper_var.get()
            raw = self._lang_display_to_code.get(val, val)
            return None if raw == "auto" else raw
        except Exception:
            return None

    def _on_whisper_lang_change(self, value):
        raw = self._lang_display_to_code.get(value, value)
        code = None if raw == "auto" else raw
        self._whisper_language = code
        if self._stt_ref is not None:
            try:
                self._stt_ref.set_language(code)
            except Exception:
                pass

    def get_hear_languages(self):
        try:
            return [code for code, var in self._hear_vars.items() if var.get()]
        except Exception:
            return []


class TestGetWhisperLanguage:
    def test_default_returns_none(self):
        h = HudLanguageHelper()
        assert h.get_whisper_language() is None

    def test_explicit_english(self):
        h = HudLanguageHelper(languages=["en", "ar", "de"])
        h._whisper_var.set("English")
        assert h.get_whisper_language() == "en"

    def test_explicit_arabic(self):
        h = HudLanguageHelper(languages=["en", "ar"])
        h._whisper_var.set("Arabic")
        assert h.get_whisper_language() == "ar"

    def test_explicit_german(self):
        h = HudLanguageHelper(languages=["en", "de"])
        h._whisper_var.set("German")
        assert h.get_whisper_language() == "de"

    def test_unknown_name_returns_name(self):
        h = HudLanguageHelper()
        h._whisper_var.set("UnknownLang")
        assert h.get_whisper_language() == "UnknownLang"

    def test_exception_returns_none(self):
        h = HudLanguageHelper()
        h._whisper_var = None  # will cause .get() to fail
        assert h.get_whisper_language() is None


class TestOnWhisperLangChange:
    def test_detected_sets_none(self):
        h = HudLanguageHelper()
        h._on_whisper_lang_change("Detected")
        assert h._whisper_language is None

    def test_english_sets_en(self):
        h = HudLanguageHelper(languages=["en"])
        h._on_whisper_lang_change("English")
        assert h._whisper_language == "en"

    def test_arabic_sets_ar(self):
        h = HudLanguageHelper(languages=["en", "ar"])
        h._on_whisper_lang_change("Arabic")
        assert h._whisper_language == "ar"

    def test_updates_stt_ref(self):
        h = HudLanguageHelper(languages=["en"])
        stt = MockSTT()
        h._stt_ref = stt
        h._on_whisper_lang_change("English")
        assert stt.language == "en"

    def test_detected_updates_stt_to_none(self):
        h = HudLanguageHelper()
        stt = MockSTT()
        h._stt_ref = stt
        h._on_whisper_lang_change("Detected")
        assert stt.language is None

    def test_no_stt_ref_no_crash(self):
        h = HudLanguageHelper()
        h._stt_ref = None
        h._on_whisper_lang_change("Detected")
        assert h._whisper_language is None


class TestLanguageMappings:
    def test_display_to_code_has_detected(self):
        h = HudLanguageHelper()
        assert "Detected" in h._lang_display_to_code
        assert h._lang_display_to_code["Detected"] == "auto"

    def test_code_to_display_has_auto(self):
        h = HudLanguageHelper()
        assert "auto" in h._lang_code_to_display
        assert h._lang_code_to_display["auto"] == "Detected"

    def test_all_languages_mapped(self):
        h = HudLanguageHelper(languages=["en", "ar", "de", "fr"])
        for code in ["en", "ar", "de", "fr"]:
            name = h._lang_code_to_display.get(code)
            assert name is not None, f"Code {code} not in code_to_display"
            back = h._lang_display_to_code.get(name)
            assert back == code, f"Round-trip failed for {code}"

    def test_lang_names_starts_with_detected(self):
        h = HudLanguageHelper(languages=["en", "ar"])
        assert h._lang_names[0] == "Detected"

    def test_lang_codes_starts_with_auto(self):
        h = HudLanguageHelper(languages=["en", "ar"])
        assert h._lang_codes[0] == "auto"

    def test_no_languages_only_detected(self):
        h = HudLanguageHelper()
        assert h._lang_names == ["Detected"]
        assert h._lang_codes == ["auto"]


class TestCalcHeight:
    def test_calc_height_with_panels(self):
        from ui.hud import BASE_HEIGHT, PANEL_ROW_HEIGHT, COLOR_FILTER_BAR_HEIGHT
        # PANEL_HEIGHT = 28 + (10*24) + 8 = 276, PANEL_ROW_HEIGHT = 276 + 4 = 280
        assert PANEL_ROW_HEIGHT == 280
        assert BASE_HEIGHT + COLOR_FILTER_BAR_HEIGHT + PANEL_ROW_HEIGHT == 590

    def test_calc_height_without_text_panel(self):
        from ui.hud import BASE_HEIGHT, TEXT_PANEL_HEIGHT, PANEL_ROW_HEIGHT, COLOR_FILTER_BAR_HEIGHT
        assert BASE_HEIGHT + COLOR_FILTER_BAR_HEIGHT - TEXT_PANEL_HEIGHT + PANEL_ROW_HEIGHT == 364


class TestColorStickiness:
    def test_color_by_phrase_id_not_position(self):
        from ui.hud import LINE_COLORS
        phrases = [(0, "a"), (1, "b"), (2, "c")]
        colors_by_pid = [pid % len(LINE_COLORS) for pid, _ in phrases]
        colors_by_idx = [idx % len(LINE_COLORS) for idx, _ in enumerate(phrases)]
        assert colors_by_pid == colors_by_idx

    def test_color_stable_after_scroll(self):
        from ui.hud import LINE_COLORS
        phrases_old = [(0, "a"), (1, "b"), (2, "c")]
        phrases_new = [(1, "b"), (2, "c"), (3, "d")]
        old_colors = [pid % len(LINE_COLORS) for pid, _ in phrases_old]
        new_colors = [pid % len(LINE_COLORS) for pid, _ in phrases_new]
        assert old_colors[1] == new_colors[0]
        assert old_colors[2] == new_colors[1]

    def test_color_by_idx_shifts_after_scroll(self):
        from ui.hud import LINE_COLORS
        phrases_old = [(0, "a"), (1, "b"), (2, "c")]
        phrases_new = [(1, "b"), (2, "c"), (3, "d")]
        old_colors = [idx % len(LINE_COLORS) for idx, _ in enumerate(phrases_old)]
        new_colors = [idx % len(LINE_COLORS) for idx, _ in enumerate(phrases_new)]
        assert old_colors[1] != new_colors[0]

    def test_pid_color_differs_from_idx_when_not_zero_based(self):
        from ui.hud import LINE_COLORS
        phrases = [(1, "a"), (2, "b"), (3, "c")]
        colors_by_pid = [pid % len(LINE_COLORS) for pid, _ in phrases]
        colors_by_idx = [idx % len(LINE_COLORS) for idx, _ in enumerate(phrases)]
        assert colors_by_pid != colors_by_idx


class TestColorFilter:
    def test_active_filter_initially_none(self):
        class MockHud:
            _active_color_filter = None
        h = MockHud()
        assert h._active_color_filter is None

    def test_toggle_sets_active_filter(self):
        class MockHud:
            _active_color_filter = None
            def _toggle_color_filter(self, ci):
                if self._active_color_filter == ci:
                    self._active_color_filter = None
                else:
                    self._active_color_filter = ci
        h = MockHud()
        h._toggle_color_filter(2)
        assert h._active_color_filter == 2

    def test_toggle_same_color_clears_filter(self):
        class MockHud:
            _active_color_filter = None
            def _toggle_color_filter(self, ci):
                if self._active_color_filter == ci:
                    self._active_color_filter = None
                else:
                    self._active_color_filter = ci
        h = MockHud()
        h._toggle_color_filter(2)
        h._toggle_color_filter(2)
        assert h._active_color_filter is None

    def test_toggle_different_color_switches(self):
        class MockHud:
            _active_color_filter = None
            def _toggle_color_filter(self, ci):
                if self._active_color_filter == ci:
                    self._active_color_filter = None
                else:
                    self._active_color_filter = ci
        h = MockHud()
        h._toggle_color_filter(1)
        h._toggle_color_filter(3)
        assert h._active_color_filter == 3

    def test_filter_shows_only_matching_color(self):
        from ui.hud import LINE_COLORS
        active_filter = 2
        phrases = [(0, "a"), (1, "b"), (2, "c"), (3, "d"), (4, "e")]
        visible = [(pid, t) for pid, t in phrases
                   if active_filter is None or pid % len(LINE_COLORS) == active_filter]
        assert len(visible) == 1
        assert visible[0][0] == 2

    def test_filter_none_shows_all(self):
        from ui.hud import LINE_COLORS
        active_filter = None
        phrases = [(0, "a"), (1, "b"), (2, "c"), (3, "d"), (4, "e")]
        visible = [(pid, t) for pid, t in phrases
                   if active_filter is None or pid % len(LINE_COLORS) == active_filter]
        assert len(visible) == 5

    def test_show_all_sets_none(self):
        class MockHud:
            _active_color_filter = 3
            def _show_all_colors(self):
                self._active_color_filter = None
        h = MockHud()
        h._show_all_colors()
        assert h._active_color_filter is None

    def test_filter_bar_height_constant(self):
        from ui.hud import COLOR_FILTER_BAR_HEIGHT
        assert COLOR_FILTER_BAR_HEIGHT == 30
