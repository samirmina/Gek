package com.gek.audio;

public interface TtsEngine extends AutoCloseable {
    void speak(String text, String lang) throws Exception;
    void stop();
    boolean isSupported();
    @Override default void close() { stop(); }
}
