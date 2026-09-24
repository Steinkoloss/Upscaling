package io.github.steinkoloss.upscaling;

/**
 * Runtime settings. For now these come from JVM properties and keybinds only;
 * a real options screen comes later.
 *
 * <p>{@code -Dupscaling.scale=0.5} sets the initial render scale (0.25..1.0),
 * {@code -Dupscaling.enabled=false} starts with upscaling off,
 * {@code -Dupscaling.jitter=true} jitters the camera (only useful once a temporal
 * upscaler consumes it; with the bilinear stretch it just makes the image shimmer),
 * {@code -Dupscaling.debugView=motion|orientation|reprojection} starts in a debug view,
 * {@code -Dupscaling.debugSpin=30} turns the camera by that many degrees per second
 * (with a slow pitch sway) so motion vectors can be checked without a mouse,
 * {@code -Dupscaling.debugGlide=4} moves the player forward at that many blocks per
 * second at a fixed height, to check camera translation on its own.
 */
public final class UpscalingConfig {
	/** Render scales the cycle keybind steps through, matching the usual upscaler quality presets. */
	private static final float[] SCALE_PRESETS = {1.0f / 1.5f, 1.0f / 1.7f, 0.5f, 1.0f / 3.0f};
	private static final String[] PRESET_NAMES = {"Quality", "Balanced", "Performance", "Ultra Performance"};

	private static boolean enabled = Boolean.parseBoolean(System.getProperty("upscaling.enabled", "true"));
	private static float scale = clampScale(parseFloat(System.getProperty("upscaling.scale"), 0.5f));
	private static final boolean jitter = Boolean.parseBoolean(System.getProperty("upscaling.jitter", "false"));
	private static final float debugSpin = parseFloat(System.getProperty("upscaling.debugSpin"), 0.0f);
	private static final float debugGlide = parseFloat(System.getProperty("upscaling.debugGlide"), 0.0f);
	private static DebugView debugView = DebugView.fromName(System.getProperty("upscaling.debugView", "off"));

	/** Order matters: the ordinal is the mode number the debug shader switches on. */
	public enum DebugView {
		OFF("Off"),
		MOTION("Motion vectors"),
		ORIENTATION("Orientation (red below camera, green above)"),
		REPROJECTION("Reprojection error");

		private final String displayName;

		DebugView(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return this.displayName;
		}

		static DebugView fromName(String name) {
			for (DebugView view : values()) {
				if (view.name().equalsIgnoreCase(name)) {
					return view;
				}
			}
			return OFF;
		}
	}

	private UpscalingConfig() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static float scale() {
		return scale;
	}

	public static boolean jitter() {
		return jitter;
	}

	public static float debugSpin() {
		return debugSpin;
	}

	public static float debugGlide() {
		return debugGlide;
	}

	public static DebugView debugView() {
		return debugView;
	}

	public static DebugView cycleDebugView() {
		debugView = DebugView.values()[(debugView.ordinal() + 1) % DebugView.values().length];
		return debugView;
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
