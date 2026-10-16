"""Argos Translate wrapper for multi-language translation."""

import json
import re
from pathlib import Path

import argostranslate.translate


class Translator:
    """Wrapper around Argos Translate with synonym enhancement and fallback."""

    def __init__(self):
        self._synonyms: dict[str, dict[str, str]] = {}
        self._loaded_pairs: set[tuple[str, str]] = set()
        self._configure_package_dirs()
        self._load_synonyms()
        self._preload_models()

    def _configure_package_dirs(self):
        """Add our custom package directory to argostranslate search path."""
        import argostranslate.settings
        custom_dir = Path(__file__).resolve().parent.parent / "resources" / "models" / "argos" / "packages"
        if custom_dir.is_dir():
            dirs = [str(d) for d in argostranslate.settings.package_dirs]
            if str(custom_dir) not in dirs:
                argostranslate.settings.package_dirs.insert(0, custom_dir)

    def _preload_models(self):
        """Preload installed language pairs sequentially to avoid MKL memory exhaustion."""
        import logging
        logger = logging.getLogger(__name__)
        try:
            available = argostranslate.translate.get_installed_languages()
            for src in available:
                for tgt in available:
                    if src.code != tgt.code:
                        try:
                            src.get_translation(tgt)
                            self._loaded_pairs.add((src.code, tgt.code))
                        except Exception as e:
                            logger.debug("Pair preload skipped %s→%s: %s", src.code, tgt.code, e)
            logger.info(f"Preloaded {len(self._loaded_pairs)} translation pairs")
        except Exception as e:
            logger.warning(f"Model preload error: {e}")

    def _load_synonyms(self):
        """Load synonym JSON files from data/synonyms/."""
        syn_dir = Path(__file__).resolve().parent.parent / "resources" / "data" / "synonyms"
        if not syn_dir.is_dir():
            return
        for f in syn_dir.glob("*.json"):
            lang = f.stem
            try:
                with open(f, encoding="utf-8") as fh:
                    data = json.load(fh)
                    self._synonyms[lang] = data.get("synonyms", data) if isinstance(data, dict) else {}
            except Exception:
                continue

    def get_installed_languages(self) -> set[str]:
        """Return set of installed language codes."""
        return {lang.code for lang in argostranslate.translate.get_installed_languages()}

    def _apply_synonyms(self, text: str, lang: str) -> str:
        """Replace text tokens with synonyms if available."""
        mapping = self._synonyms.get(lang, {})
        if not mapping:
            return text
        for key, val in mapping.items():
            text = re.sub(r'\b' + re.escape(key) + r'\b', val, text)
        return text

    def _is_repetitive(self, text: str, source_text: str = "") -> bool:
        """Detect NMT hallucination: repetitive translations, symbol floods, etc."""
        if not text or not text.strip():
            return False
        lower = text.lower().strip()
        words = lower.split()

        # 1. Length sanity: translation >5x longer than source is suspicious
        if source_text and len(source_text.strip()) > 0:
            ratio = len(text) / len(source_text)
            if ratio > 5.0:
                return True

        # 2. Symbol/dot flood: "st.st.st.st.st..." or "ha.ha.ha.ha..."
        if re.search(r'(\w{1,6}\.){4,}', lower):
            return True

        # 3. Character flood: "xxxxxxxxxx" or "ststststst" (same char or 2-char repeat)
        if re.search(r'(.)\1{5,}', lower.replace(" ", "")):
            return True
        if re.search(r'(.{1,2})\1{5,}', lower.replace(" ", "")):
            return True

        # 4. Word repetition: same word 5+ times in a row
        if len(words) >= 5:
            for i in range(len(words) - 4):
                if words[i] == words[i+1] == words[i+2] == words[i+3] == words[i+4]:
                    return True

        # 5. Phrase repetition: 2-4 word phrase repeated 4+ times
        for n in range(2, min(5, max(2, len(words) // 4 + 1))):
            if len(words) < n * 4:
                continue
            phrase_counts = {}
            for i in range(len(words) - n + 1):
                phrase = " ".join(words[i:i+n])
                phrase_counts[phrase] = phrase_counts.get(phrase, 0) + 1
            for phrase, count in phrase_counts.items():
                if count >= 4:
                    return True

        # 6. Punctuation flood: more than 40% punctuation in short text
        punct_count = sum(1 for c in text if c in ".,!?;:…。，！？、")
        if punct_count > len(text) * 0.4 and len(text) < 50:
            return True

        # 7. Repeated punctuation: "!!!", "???", "......"
        if re.search(r'([.,!?;:…。，！？、])\1{3,}', text):
            return True

        return False

    def translate(self, text: str, source_lang: str, target_lang: str) -> str:
        """Translate text with fallback through English."""
        import logging
        logger = logging.getLogger(__name__)

        logger.debug(f"Translator.translate: {source_lang}->{target_lang} text={text[:60]!r}")
        if not text.strip():
            return text
        if source_lang == target_lang:
            return text

        result = self._direct_translate(text, source_lang, target_lang)
        if result and result.strip() and result.strip() != text.strip():
            if not self._is_repetitive(result, text):
                return result

        via_en_result = None

        if source_lang != "en" and target_lang != "en":
            via_en_result = self._direct_translate(text, source_lang, "en")
            if via_en_result and via_en_result.strip() and via_en_result.strip() != text.strip():
                if not self._is_repetitive(via_en_result, text):
                    final = self._direct_translate(via_en_result, "en", target_lang)
                    if final and final.strip() and final.strip() != text.strip():
                        if not self._is_repetitive(final, text):
                            return final

        if source_lang != "en":
            if via_en_result is None:
                via_en_result = self._direct_translate(text, source_lang, "en")
            if via_en_result and via_en_result.strip() and via_en_result.strip() != text.strip():
                if not self._is_repetitive(via_en_result, text):
                    logger.warning(
                        f"Fallback {source_lang}->en succeeded for target {target_lang}"
                    )
                    return via_en_result

        if target_lang != "en" and via_en_result and via_en_result.strip():
            final = self._direct_translate(via_en_result, "en", target_lang)
            if final and final.strip() and final.strip() != text.strip():
                if not self._is_repetitive(final, text):
                    logger.warning(
                        f"Fallback en->{target_lang} succeeded from source {source_lang}"
                    )
                    return final

        logger.warning(
            f"All translation paths failed: {source_lang}->{target_lang} "
            f"for text={text[:50]!r}..."
        )
        return text

    def _direct_translate(self, text: str, source_lang: str, target_lang: str) -> str:
        """Translate using argostranslate with synonym enhancement."""
        import logging
        logger = logging.getLogger(__name__)

        logger.debug(f"Translator._direct_translate: {source_lang}->{target_lang}")
        try:
            enhanced = self._apply_synonyms(text, source_lang)
            result = argostranslate.translate.translate(enhanced, source_lang, target_lang)
            if not result or not result.strip():
                logger.debug(f"Translator._direct_translate: empty result")
                return text
            logger.debug(f"Translator._direct_translate: result={result[:60]!r}")
            return result
        except Exception as e:
            logger.warning(f"Translation {source_lang}->{target_lang} failed: {e}")
            return text

    def translate_to_multiple(
        self, text: str, source_lang: str, target_languages: list[str]
    ) -> dict[str, str]:
        """Translate text to multiple target languages."""
        import logging
        logger = logging.getLogger(__name__)

        logger.debug(f"Translator.translate_to_multiple: source={source_lang} targets={target_languages}")
        out: dict[str, str] = {}
        for tgt in target_languages:
            out[tgt] = self.translate(text, source_lang, tgt)
        return out
