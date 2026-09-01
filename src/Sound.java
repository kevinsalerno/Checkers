import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

// Synthesized sound effects (no asset files). Each effect is a short sequence
// of sine tones played on a daemon thread so the UI never blocks.
public class Sound {
	private static final float RATE = 44100f;

	public static void move()    { play(new int[]{440}, new int[]{70}); }
	public static void capture() { play(new int[]{660, 880}, new int[]{60, 90}); }
	public static void king()    { play(new int[]{523, 659, 784}, new int[]{80, 80, 140}); }
	public static void invalid() { play(new int[]{180, 140}, new int[]{90, 120}); }
	public static void win()     { play(new int[]{523, 659, 784, 1046}, new int[]{120, 120, 120, 260}); }

	private static void play(final int[] hz, final int[] ms) {
		Thread t = new Thread(new Runnable() {
			public void run() {
				try {
					AudioFormat af = new AudioFormat(RATE, 8, 1, true, false);
					SourceDataLine line = AudioSystem.getSourceDataLine(af);
					line.open(af);
					line.start();
					for (int n = 0; n < hz.length; n++) {
						byte[] buf = new byte[(int) (RATE * ms[n] / 1000)];
						for (int i = 0; i < buf.length; i++) {
							double angle = i / (RATE / hz[n]) * 2 * Math.PI;
							double fade = Math.min(1.0, (buf.length - i) / (RATE * 0.01)); // avoid click
							buf[i] = (byte) (Math.sin(angle) * 110 * fade);
						}
						line.write(buf, 0, buf.length);
					}
					line.drain();
					line.close();
				} catch (Exception ignored) {
					// No audio device (e.g. headless) -> silently skip.
				}
			}
		});
		t.setDaemon(true);
		t.start();
	}
}
