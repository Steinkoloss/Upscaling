package io.github.steinkoloss.upscaling;

/**
 * Sub-pixel camera jitter for temporal upscalers: a Halton(2,3) sequence whose
 * length scales with the upscale ratio, as AMD recommends for FSR
 * (8 * (display / render)^2 phases).
 */
public final class Jitter {
	private static int frameIndex;
	private static float x;
	private static float y;

	private Jitter() {
	}

	/** Advances to the next sample; offsets are in render-resolution pixels, within [-0.5, 0.5). */
	public static void advance(int renderWidth, int displayWidth) {
		float ratio = (float) displayWidth / Math.max(1, renderWidth);
		int phaseCount = Math.max(1, (int) Math.ceil(8.0f * ratio * ratio));
		frameIndex = (frameIndex + 1) % phaseCount;
		// Halton is 1-based; index 0 would always give (0, 0).
		x = halton(frameIndex + 1, 2) - 0.5f;
		y = halton(frameIndex + 1, 3) - 0.5f;
	}

	public static float x() {
		return x;
	}

	public static float y() {
		return y;
	}

	static float halton(int index, int base) {
		float fraction = 1.0f;
		float result = 0.0f;
		while (index > 0) {
			fraction /= base;
			result += fraction * (index % base);
			index /= base;
		}
		return result;
	}
}
