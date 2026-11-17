package com.gek.audio;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

public final class AudioDevices {
    private AudioDevices() {}

    public static List<String> inputDeviceNames() {
        List<String> out = new ArrayList<>();
        AudioFormat fmt = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(info)) out.add(mi.getName());
            } catch (Exception ignored) {}
        }
        return out;
    }
}
