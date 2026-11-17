package com.gek.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class NoopLoopbackCapture implements LoopbackCapture {
    private static final Logger log = LoggerFactory.getLogger(NoopLoopbackCapture.class);
    private Consumer<AudioChunk> consumer;
    @Override public void setConsumer(Consumer<AudioChunk> consumer) { this.consumer = consumer; }
    @Override public void start() { log.info("Loopback capture not available (requires platform implementation: WASAPI on Windows, Pulse/PipeWire monitor on Linux)."); }
    @Override public void stop() {}
    @Override public boolean isRunning() { return false; }
    @Override public String name() { return "loopback-noop"; }
    @Override public void close() {}
}
