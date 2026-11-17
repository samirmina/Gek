package com.gek.inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopTextInjector implements TextInjector {
    private static final Logger log = LoggerFactory.getLogger(NoopTextInjector.class);
    @Override public boolean inject(String text) { log.info("[noop inject] {}", text); return false; }
    @Override public String name() { return "noop"; }
}
