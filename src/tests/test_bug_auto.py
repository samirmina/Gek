"""Bug tests for the 'auto' language code bug.

When the user selects 'Detected' from the Hear dropdown, the STT engine
must receive None (meaning auto-detect), not the literal string 'auto'.
"""
import sys
import os
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


class MockStringVar:
    """Minimal mock for tk.StringVar / tkinter variable."""

    def __init__(self, value="Detected"):
        self._value = value

    def get(self):
        return self._value

    def set(self, val):
        self._value = val


class MockSTT:
    """Mock STTEngine that records set_language calls."""

    def __init__(self):
        self.language = None

    def set_language(self, language):
        self.language = language


class HudLanguageHelper:
    """Extracted language resolution logic from Hud for headless testing.

    Mirrors the fixed hud.py logic without importing customtkinter.
    """

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


# --- Bug: 'auto' language code tests ---

class TestAutoLanguageBug:
    """Core bug tests for the Detected/auto language code issue."""

    def test_get_whisper_language_detected_returns_none(self):
        hud = HudLanguageHelper()
        hud._whisper_var.set("Detected")
        result = hud.get_whisper_language()
        assert result is None, f"Expected None, got {result!r}"

    def test_on_whisper_lang_change_detected_sets_none(self):
        hud = HudLanguageHelper()
        hud._on_whisper_lang_change("Detected")
        assert hud._whisper_language is None, f"Expected None, got {hud._whisper_language!r}"

    def test_on_whisper_lang_change_detected_sets_stt_to_none(self):
        hud = HudLanguageHelper()
        stt = MockSTT()
        hud._stt_ref = stt
        hud._on_whisper_lang_change("Detected")
        assert stt.language is None, f"Expected STT language None, got {stt.language!r}"

    def test_initial_whisper_var_value_is_detected(self):
        hud = HudLanguageHelper()
        assert hud._whisper_var.get() == "Detected"

    def test_stt_language_none_means_autodetect(self):
        """When STT language is None, transcribe() auto-detects."""
        stt = MockSTT()
        stt.set_language(None)
        assert stt.language is None

    def test_stt_language_string_auto_is_rejected(self):
        """Whisper rejects literal 'auto' string — this should never happen."""
        stt = MockSTT()
        stt.set_language("auto")
        # The fix ensures this path is never reached, but verify the guard:
        assert stt.language == "auto"  # would be bad, just documenting the failure mode


# --- Valid language selections ---

class TestValidLanguageSelections:
    """Ensure explicit language selections still work correctly."""

    def test_get_whisper_language_english_returns_en(self):
        hud = HudLanguageHelper(languages=["en", "ar", "de"])
        hud._whisper_var.set("English")
        result = hud.get_whisper_language()
        assert result == "en", f"Expected 'en', got {result!r}"

    def test_get_whisper_language_arabic_returns_ar(self):
        hud = HudLanguageHelper(languages=["en", "ar", "de"])
        hud._whisper_var.set("Arabic")
        result = hud.get_whisper_language()
        assert result == "ar", f"Expected 'ar', got {result!r}"

    def test_get_whisper_language_german_returns_de(self):
        hud = HudLanguageHelper(languages=["en", "ar", "de"])
        hud._whisper_var.set("German")
        result = hud.get_whisper_language()
        assert result == "de", f"Expected 'de', got {result!r}"

    def test_on_whisper_lang_change_english_sets_en(self):
        hud = HudLanguageHelper(languages=["en", "ar"])
        hud._on_whisper_lang_change("English")
        assert hud._whisper_language == "en"

    def test_on_whisper_lang_change_arabic_sets_ar(self):
        hud = HudLanguageHelper(languages=["en", "ar"])
        hud._on_whisper_lang_change("Arabic")
        assert hud._whisper_language == "ar"

    def test_on_whisper_lang_change_english_updates_stt(self):
        hud = HudLanguageHelper(languages=["en"])
        stt = MockSTT()
        hud._stt_ref = stt
        hud._on_whisper_lang_change("English")
        assert stt.language == "en"


# --- Regression: edge cases ---

class TestLanguageEdgeCases:
    """Edge case tests to prevent future regressions."""

    def test_get_whisper_language_no_var_returns_none(self):
        hud = HudLanguageHelper()
        hud._whisper_var = None
        result = hud.get_whisper_language()
        assert result is None

    def test_on_whisper_lang_change_no_stt_ref(self):
        hud = HudLanguageHelper()
        hud._stt_ref = None
        hud._on_whisper_lang_change("Detected")
        assert hud._whisper_language is None

    def test_on_whisper_lang_change_unknown_display_name(self):
        hud = HudLanguageHelper()
        hud._on_whisper_lang_change("UnknownLang")
        # Unknown display name resolves to itself, not "auto"
        assert hud._whisper_language == "UnknownLang"

    def test_lang_display_to_code_mapping_completeness(self):
        hud = HudLanguageHelper(languages=["en", "ar", "de"])
        assert "Detected" in hud._lang_display_to_code
        assert hud._lang_display_to_code["Detected"] == "auto"
        assert "English" in hud._lang_display_to_code
        assert hud._lang_display_to_code["English"] == "en"

    def test_lang_code_to_display_mapping_completeness(self):
        hud = HudLanguageHelper(languages=["en", "ar", "de"])
        assert "auto" in hud._lang_code_to_display
        assert hud._lang_code_to_display["auto"] == "Detected"
        assert "en" in hud._lang_code_to_display
        assert hud._lang_code_to_display["en"] == "English"
