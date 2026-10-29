import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.awt.GraphicsEnvironment;

/**
 * Synthesised sound effects using only {@code javax.sound.sampled}.
 * No audio files are needed: tones and noise bursts are generated as
 * PCM samples. All playback happens on daemon threads and failures
 * (headless environment, no audio device) are swallowed silently so
 * gameplay is never blocked by sound.
 */
public final class SoundEffects {
    private static final int SAMPLE_RATE = 22050;
    private static volatile boolean enabled = true;

    private SoundEffects() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void playMove() {
        playTone(660, 70, 0.35, false);
    }

    public static void playSelect() {
        playTone(520, 45, 0.22, false);
    }

    public static void playCapture() {
        playNoise(120, 0.5);
        playTone(220, 110, 0.4, false);
    }

    public static void playCheck() {
        playTone(880, 90, 0.35, false);
        playTone(1174, 130, 0.35, false);
    }

    public static void playIllegal() {
        playTone(140, 160, 0.4, false);
    }

    public static void playWin() {
        if (!enabled) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                toneSync(523, 130, 0.35);
                toneSync(659, 130, 0.35);
                toneSync(784, 130, 0.35);
                toneSync(1046, 260, 0.4);
            }
        }, "sound-win").start();
    }

    public static void playDraw() {
        playTone(440, 200, 0.35, false);
        playTone(440, 200, 0.35, false);
    }

    private static void playTone(final double freq, final int ms,
                                 final double volume, boolean sync) {
        if (!enabled || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                toneSync(freq, ms, volume);
            }
        };
        if (sync) {
            task.run();
        } else {
            Thread t = new Thread(task, "sound-tone");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void playNoise(final int ms, final double volume) {
        if (!enabled || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = new byte[(int) (SAMPLE_RATE * ms / 1000.0) * 2];
                    java.util.Random rnd = new java.util.Random(42);
                    for (int i = 0; i < data.length; i += 2) {
                        double env = 1.0 - (double) i / data.length;
                        short s = (short) (rnd.nextGaussian() * 9000 * env * volume * 2);
                        data[i] = (byte) (s & 0xFF);
                        data[i + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                    writePcm(data);
                } catch (Exception ignored) {
                    // Sound must never break the game.
                }
            }
        }, "sound-noise");
        t.setDaemon(true);
        t.start();
    }

    private static void toneSync(double freq, int ms, double volume) {
        try {
            int frames = (int) (SAMPLE_RATE * ms / 1000.0);
            byte[] data = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                double env = Math.min(1.0, i / (SAMPLE_RATE * 0.008))
                        * (1.0 - (double) i / frames);
                double v = Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE) * env * volume;
                short s = (short) (v * 30000);
                data[2 * i] = (byte) (s & 0xFF);
                data[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
            }
            writePcm(data);
        } catch (Exception ignored) {
            // Sound must never break the game.
        }
    }

    private static void writePcm(byte[] data) throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt);
        try {
            line.start();
            line.write(data, 0, data.length);
            line.drain();
        } finally {
            line.close();
        }
    }
}
