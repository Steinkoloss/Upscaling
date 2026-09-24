package io.github.steinkoloss.upscaling;

import io.github.steinkoloss.upscaling.fsr.FfxApi;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

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
 * second at a fixed height, to check camera translation on its own,
 * {@code -Dupscaling.upscaler=fsr4|bilinear} picks the upscaler (FSR 4 falls back to
 * bilinear when unavailable), {@code -Dupscaling.fsr4vk=<dir>} points at the output of
 * tools/build-fsr4vk.sh (default: {@code <game dir>/upscaling/fsr4vk}),
 * {@code -Dupscaling.fsr4.version=4.1.1|4.0.2} picks the FSR 4 model and
 * {@code -Dupscaling.fsr4.log=true} makes fsr4vk write provider.log next to its assets.
 */
public final class UpscalingConfig {
	/** Render scales the cycle keybind steps through, matching the usual upscaler quality presets. */
	private static final float[] SCALE_PRESETS = {1.0f, 1.0f / 1.5f, 1.0f / 1.7f, 0.5f, 1.0f / 3.0f};
	private static final String[] PRESET_NAMES = {"Native AA", "Quality", "Balanced", "Performance", "Ultra Performance"};

	private static boolean enabled = Boolean.parseBoolean(System.getProperty("upscaling.enabled", "true"));
	private static float scale = clampScale(parseFloat(System.getProperty("upscaling.scale"), 0.5f));
	private static final boolean jitter = Boolean.parseBoolean(System.getProperty("upscaling.jitter", "false"));
	private static final float debugSpin = parseFloat(System.getProperty("upscaling.debugSpin"), 0.0f);
	private static final float debugGlide = parseFloat(System.getProperty("upscaling.debugGlide"), 0.0f);
	private static DebugView debugView = DebugView.fromName(System.getProperty("upscaling.debugView", "off"));
	private static Upscaler upscaler = "bilinear".equalsIgnoreCase(System.getProperty("upscaling.upscaler")) ? Upscaler.BILINEAR : Upscaler.FSR4;

	public enum Upscaler {
		FSR4("FSR 4"),
		BILINEAR("Bilinear");

		private final String displayName;

		Upscaler(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return this.displayName;
		}
	}

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

	public static Upscaler upscaler() {
		return upscaler;
	}

	public static Upscaler cycleUpscaler() {
		upscaler = Upscaler.values()[(upscaler.ordinal() + 1) % Upscaler.values().length];
		return upscaler;
	}

	public static Path fsr4vkDirectory() {
		String configured = System.getProperty("upscaling.fsr4vk");
		return configured != null ? Path.of(configured) : FabricLoader.getInstance().getGameDir().resolve("upscaling").resolve("fsr4vk");
	}

	public static long fsr4Version() {
		return "4.0.2".equals(System.getProperty("upscaling.fsr4.version")) ? FfxApi.VERSION_FSR_4_0_2 : FfxApi.VERSION_FSR_4_1_1;
	}

	/** Software Vulkan (lavapipe) crashes in fsr4vk's shaders, so it is skipped unless forced. */
	public static boolean fsr4AllowSoftware() {
		return Boolean.parseBoolean(System.getProperty("upscaling.fsr4.allowSoftware", "false"));
	}

	public static boolean fsr4ProviderLog() {
		return Boolean.parseBoolean(System.getProperty("upscaling.fsr4.log", "false"));
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
