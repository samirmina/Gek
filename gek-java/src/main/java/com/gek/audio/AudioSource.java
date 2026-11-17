package com.gek.audio;

import java.util.function.Consumer;

public interface AudioSource extends AutoCloseable {
    void setConsumer(Consumer<AudioChunk> consumer);
    void start() throws Exception;
    void stop();
    boolean isRunning();
    String name();
    @Override default void close() { stop(); }
}
