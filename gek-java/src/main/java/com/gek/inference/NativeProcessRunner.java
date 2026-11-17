package com.gek.inference;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class NativeProcessRunner {
    private NativeProcessRunner() {}

    public static NativeProcessResult run(List<String> command, Path workDir, Map<String,String> env, Duration timeout) throws Exception {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir.toFile());
        if (env != null && !env.isEmpty()) pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StreamGobble out = new StreamGobble(p.getInputStream());
        StreamGobble err = new StreamGobble(p.getErrorStream());
        Thread t1 = new Thread(out, "native-proc-out"); t1.setDaemon(true); t1.start();
        Thread t2 = new Thread(err, "native-proc-err"); t2.setDaemon(true); t2.start();
        boolean finished = p.waitFor(timeout == null ? 600_000 : timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            t1.join(200); t2.join(200);
            return new NativeProcessResult(-1, out.text(), err.text() + "\n[TIMEOUT]", System.currentTimeMillis()-start);
        }
        t1.join(1000); t2.join(1000);
        return new NativeProcessResult(p.exitValue(), out.text(), err.text(), System.currentTimeMillis()-start);
    }

    private static final class StreamGobble implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamGobble(InputStream in) { this.in = in; }
        @Override public void run() {
            try (InputStream is = in) {
                byte[] b = new byte[8192]; int n; while ((n = is.read(b)) >= 0) buf.write(b,0,n);
            } catch (Exception ignored) {}
        }
        String text() { return buf.toString(StandardCharsets.UTF_8); }
    }
}
