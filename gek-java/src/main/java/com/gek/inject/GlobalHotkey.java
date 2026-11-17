package com.gek.inject;

import java.io.Closeable;

/** Global hotkey registration (Windows implementation via JNA; validate on Windows). */
public interface GlobalHotkey extends Closeable {
    void start() throws Exception;
    void stop();
    boolean isSupported();
    @Override void close();
}
