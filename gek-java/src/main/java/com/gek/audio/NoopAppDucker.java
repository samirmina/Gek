package com.gek.audio;

public class NoopAppDucker implements AppDucker {
    @Override public void duck() {}
    @Override public void unduck() {}
    @Override public boolean isSupported() { return false; }
}
