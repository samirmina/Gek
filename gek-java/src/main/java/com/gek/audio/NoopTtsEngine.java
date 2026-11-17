package com.gek.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopTtsEngine implements TtsEngine {
    private static final Logger log = LoggerFactory.getLogger(NoopTtsEngine.class);
    @Override public void speak(String text, String lang) { log.info("TTS not available (Windows SAPI implementation required). text={} lang={}", text, lang); }
    @Override public void stop() {}
    @Override public boolean isSupported() { return false; }
}
