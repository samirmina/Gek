package com.gek.audio;

public interface AppDucker extends AutoCloseable {
    void duck();
    void unduck();
    boolean isSupported();
    @Override default void close() { unduck(); }
}
