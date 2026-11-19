package com.gek.inference;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeProcessRunnerTest {
    @Test
    void runsSuccessfulCommand() throws Exception {
        List<String> cmd = PlatformSupport.isWindows()
                ? List.of("cmd", "/c", "<nul set /p=out& echo err 1>&2")
                : List.of("sh", "-c", "printf out; printf err >&2");
        NativeProcessResult r = NativeProcessRunner.run(cmd, null, Map.of(), Duration.ofSeconds(5));
        assertEquals(0, r.exitCode());
        assertTrue(r.ok());
        assertEquals("out", r.stdout());
        assertTrue(r.stderr().contains("err"));
        assertTrue(r.millis() >= 0);
    }

    @Test
    void returnsNonZeroExit() throws Exception {
        List<String> cmd = PlatformSupport.isWindows()
                ? List.of("cmd", "/c", "exit /b 7")
                : List.of("sh", "-c", "exit 7");
        NativeProcessResult r = NativeProcessRunner.run(cmd, null, Map.of(), Duration.ofSeconds(5));
        assertEquals(7, r.exitCode());
        assertFalse(r.ok());
    }

    @Test
    void timesOutLongRunningCommand() throws Exception {
        List<String> cmd = PlatformSupport.isWindows()
                ? List.of("cmd", "/c", "ping -n 6 127.0.0.1 >nul")
                : List.of("sh", "-c", "sleep 5");
        NativeProcessResult r = NativeProcessRunner.run(cmd, null, Map.of(), Duration.ofMillis(100));
        assertEquals(-1, r.exitCode());
        assertFalse(r.ok());
        assertTrue(r.stderr().contains("TIMEOUT"));
    }

    @Test
    void recordAccessorsWork() {
        NativeProcessResult r = new NativeProcessResult(1, "a", "b", 3);
        assertEquals(1, r.exitCode());
        assertEquals("a", r.stdout());
        assertEquals("b", r.stderr());
        assertEquals(3, r.millis());
        assertFalse(r.ok());
        assertTrue(new NativeProcessResult(0, "", "", 0).ok());
    }
}
