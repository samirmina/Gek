package com.gek.inference.diarization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal reader for pyannote speaker-diarization-community-1 config.yaml fields used in Java pipeline. */
public final class DiarizationConfig {
    public final double threshold;
    public final double fa;
    public final double fb;
    public final double minDurationOff;
    public final boolean excludeOverlap;

    public DiarizationConfig(double threshold, double fa, double fb, double minDurationOff, boolean excludeOverlap) {
        this.threshold = threshold; this.fa = fa; this.fb = fb; this.minDurationOff = minDurationOff; this.excludeOverlap = excludeOverlap;
    }

    public static DiarizationConfig load(Path pyannoteDir) {
        Path cfg = pyannoteDir.resolve("config.yaml");
        double threshold = 0.6, fa = 0.07, fb = 0.8, minOff = 0.0;
        boolean exclude = true;
        if (Files.isRegularFile(cfg)) {
            try {
                String s = Files.readString(cfg);
                threshold = findDouble(s, "threshold", threshold);
                fa = findDouble(s, "Fa", fa);
                fb = findDouble(s, "Fb", fb);
                minOff = findDouble(s, "min_duration_off", minOff);
                exclude = findBool(s, "embedding_exclude_overlap", exclude);
            } catch (IOException ignored) {}
        }
        return new DiarizationConfig(threshold, fa, fb, minOff, exclude);
    }

    private static double findDouble(String s, String key, double def) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)").matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : def;
    }
    private static boolean findBool(String s, String key, boolean def) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : def;
    }
}
