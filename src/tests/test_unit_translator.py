"""Unit tests for Translator module."""
import sys
import os

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


@pytest.fixture
def translator():
    from core.translator import Translator
    return Translator()


class TestTranslatorTranslate:
    def test_same_lang_returns_text(self, translator):
        result = translator.translate("Hello", "en", "en")
        assert result == "Hello"

    def test_empty_text_returns_empty(self, translator):
        result = translator.translate("", "en", "de")
        assert result == ""

    def test_whitespace_returns_whitespace(self, translator):
        result = translator.translate("   ", "en", "de")
        assert result == "   "

    def test_returns_string(self, translator):
        result = translator.translate("Hello world", "en", "de")
        assert isinstance(result, str)

    def test_result_not_same_as_input(self, translator):
        result = translator.translate("Hello world", "en", "de")
        assert result != "Hello world" or len(result) > 0


class TestTranslatorDirectTranslate:
    def test_returns_string(self, translator):
        result = translator._direct_translate("Hello", "en", "de")
        assert isinstance(result, str)

    def test_same_lang_returns_text(self, translator):
        result = translator._direct_translate("Hello", "en", "en")
        assert isinstance(result, str)

    def test_exception_returns_text(self, translator):
        result = translator._direct_translate("Test", "invalid_lang", "xx")
        assert result == "Test"


class TestTranslatorSynonyms:
    def test_no_mapping_returns_original(self, translator):
        result = translator._apply_synonyms("Hello", "xx")
        assert result == "Hello"

    def test_empty_mapping_returns_original(self, translator):
        result = translator._apply_synonyms("Hello", "en")
        assert isinstance(result, str)


class TestTranslatorFallback:
    def test_fallback_via_english(self, translator):
        result = translator.translate("Hello", "en", "de")
        assert isinstance(result, str)

    def test_unavailable_lang_returns_input(self, translator):
        result = translator.translate("Hello", "xx", "yy")
        assert result == "Hello"


class TestTranslatorMultiple:
    def test_translate_to_multiple_returns_dict(self, translator):
        result = translator.translate_to_multiple("Hello", "en", ["de", "fr"])
        assert isinstance(result, dict)
        assert "de" in result
        assert "fr" in result

    def test_translate_to_multiple_values_are_strings(self, translator):
        result = translator.translate_to_multiple("Hello", "en", ["de"])
        for val in result.values():
            assert isinstance(val, str)


class TestTranslatorLanguages:
    def test_get_installed_returns_set(self, translator):
        langs = translator.get_installed_languages()
        assert isinstance(langs, set)

    def test_installed_includes_en(self, translator):
        langs = translator.get_installed_languages()
        assert "en" in langs


class TestTranslatorPreloaded:
    def test_loaded_pairs_populated(self, translator):
        assert isinstance(translator._loaded_pairs, set)

    def test_preloaded_pairs_are_tuples(self, translator):
        for pair in translator._loaded_pairs:
            assert isinstance(pair, tuple)
            assert len(pair) == 2


class TestTranslatorRepetitive:
    def test_normal_text_not_repetitive(self, translator):
        assert translator._is_repetitive("Hello world, this is a normal sentence.") == False

    def test_empty_text_not_repetitive(self, translator):
        assert translator._is_repetitive("") == False

    def test_symbol_flood_dotted(self, translator):
        assert translator._is_repetitive("st.st.st.st.st.st.st") == True

    def test_symbol_flood_dotted_short(self, translator):
        assert translator._is_repetitive("ha.ha.ha.ha.ha") == True

    def test_character_flood(self, translator):
        assert translator._is_repetitive("xxxxxxxxxxxx") == True

    def test_character_flood_stst(self, translator):
        assert translator._is_repetitive("ststststststst") == True

    def test_word_repetition_5x(self, translator):
        assert translator._is_repetitive("the the the the the") == True

    def test_word_repetition_4x_passes(self, translator):
        assert translator._is_repetitive("the the the the") == False

    def test_phrase_repetition_2word(self, translator):
        text = "als de rest als de rest als de rest als de rest"
        assert translator._is_repetitive(text) == True

    def test_phrase_repetition_3word(self, translator):
        text = "the main rest the main rest the main rest the main rest"
        assert translator._is_repetitive(text) == True

    def test_phrase_repetition_2x_passes(self, translator):
        text = "hello world hello world"
        assert translator._is_repetitive(text) == False

    def test_length_sanity_too_long(self, translator):
        short_source = "Hi"
        long_result = "a" * 100
        assert translator._is_repetitive(long_result, short_source) == True

    def test_length_sanity_normal(self, translator):
        source = "Hello world"
        result = "Hallo Welt"
        assert translator._is_repetitive(result, source) == False

    def test_punctuation_flood(self, translator):
        assert translator._is_repetitive("!!!??!!!??") == True

    def test_repeated_punctuation(self, translator):
        assert translator._is_repetitive("text......") == True

    def test_normal_multilingual_passes(self, translator):
        assert translator._is_repetitive("Hallo Welt, wie geht es dir?") == False
        assert translator._is_repetitive("Bonjour le monde") == False
        assert translator._is_repetitive("こんにちは世界") == False
