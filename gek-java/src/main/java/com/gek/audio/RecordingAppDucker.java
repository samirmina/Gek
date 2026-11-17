package com.gek.audio;

import java.util.concurrent.atomic.AtomicInteger;

public class RecordingAppDucker implements AppDucker {
    private final AtomicInteger duckCount = new AtomicInteger();
    private final AtomicInteger unduckCount = new AtomicInteger();
    @Override public void duck() { duckCount.incrementAndGet(); }
    @Override public void unduck() { unduckCount.incrementAndGet(); }
    @Override public boolean isSupported() { return true; }
    public int duckCount() { return duckCount.get(); }
    public int unduckCount() { return unduckCount.get(); }
}
