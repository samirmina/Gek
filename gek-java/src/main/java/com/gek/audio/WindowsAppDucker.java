package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows app-ducking integration point.
 *
 * <p>Full Core Audio per-app session ducking requires native COM integration with Windows
 * audio session APIs and is not fully implemented in this Java-only baseline.
 * This class provides a Windows-specific hook so the runtime is wired for Windows hosts;
 * if an external/helper-based implementation becomes available it can replace this.</p>
 */
public class WindowsAppDucker implements AppDucker {
    private static final Logger log = LoggerFactory.getLogger(WindowsAppDucker.class);
    private volatile boolean ducked = false;

    @Override
    public void duck() {
        if (!isSupported()) return;
        ducked = true;
        // Placeholder: real Core Audio per-app ducking to be provided by native/helper integration.
        log.debug("Windows app duck requested (best-effort placeholder active).");
    }

    @Override
    public void unduck() {
        if (!ducked) return;
        ducked = false;
        log.debug("Windows app unduck requested (best-effort placeholder active).");
    }

    @Override
    public boolean isSupported() {
        // Per-app ducking is not reliably available without additional native/helper support.
        // Return false so callers do not assume audio was actually ducked.
        return false && PlatformSupport.isWindows();
    }
}
