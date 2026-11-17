package com.gek.inject;

public interface TextInjector {
    /** Inject text at current caret/focus. Returns true on best-effort success. */
    boolean inject(String text);
    String name();
}
