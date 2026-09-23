package io.github.steinkoloss.upscaling;

/**
 * Runtime settings. For now these come from JVM properties and keybinds only;
 * a real options screen comes later.
 *
 * <p>{@code -Dupscaling.scale=0.5} sets the initial render scale (0.25..1.0),
 * {@code -Dupscaling.enabled=false} starts with upscaling off.
 */
public final class UpscalingConfig {
	/** Render scales the cycle keybind steps through, matching the usual upscaler quality presets. */
	private static final float[] SCALE_PRESETS = {1.0f / 1.5f, 1.0f / 1.7f, 0.5f, 1.0f / 3.0f};
	private static final String[] PRESET_NAMES = {"Quality", "Balanced", "Performance", "Ultra Performance"};

	private static boolean enabled = Boolean.parseBoolean(System.getProperty("upscaling.enabled", "true"));
	private static float scale = clampScale(parseFloat(System.getProperty("upscaling.scale"), 0.5f));

	private UpscalingConfig() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static float scale() {
		return scale;
	}

	public static boolean toggle() {
		enabled = !enabled;
		return enabled;
	}

	/** Steps to the next preset and returns its display name. */
	public static String cycleScale() {
		int next = 0;
		for (int i = 0; i < SCALE_PRESETS.length; i++) {
			if (Math.abs(SCALE_PRESETS[i] - scale) < 1.0e-3f) {
				next = (i + 1) % SCALE_PRESETS.length;
				break;
			}
		}
		scale = SCALE_PRESETS[next];
		return PRESET_NAMES[next];
	}

	private static float clampScale(float value) {
		return Math.max(0.25f, Math.min(1.0f, value));
	}

	private static float parseFloat(String value, float fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Float.parseFloat(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
