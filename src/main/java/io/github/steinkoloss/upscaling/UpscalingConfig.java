package io.github.steinkoloss.upscaling;

import com.mojang.logging.LogUtils;
import io.github.steinkoloss.upscaling.fsr.FfxApi;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Runtime settings. The user-facing ones (on/off, upscaler, render scale,
 * sharpness) are saved to {@code config/upscaling.properties} and edited in
 * Options -> Upscaling... or with keybinds; JVM properties override them at startup.
 *
 * <p>{@code -Dupscaling.scale=0.5} sets the render scale (0.33..1.0),
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
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("upscaling.properties");

	/** Render scale range in percent: FSR supports up to 3x upscaling. */
	public static final int MIN_SCALE_PERCENT = 33;
	public static final int MAX_SCALE_PERCENT = 100;
	/** The usual upscaler quality presets, as render scale in percent (1/1.5, 1/1.7, 1/2, 1/3). */
	private static final int[] PRESET_PERCENTS = {100, 67, 59, 50, 33};
	private static final String[] PRESET_NAMES = {"Native AA", "Quality", "Balanced", "Performance", "Ultra Performance"};

	private static boolean enabled = true;
	private static int scalePercent = 50;
	/** RCAS sharpening strength 0..1 in steps of 0.05 (stored as 0..20); 0 is off. */
	private static int sharpnessSteps = 0;
	private static final boolean jitter = Boolean.parseBoolean(System.getProperty("upscaling.jitter", "false"));
	private static final float debugSpin = parseFloat(System.getProperty("upscaling.debugSpin"), 0.0f);
	private static final float debugGlide = parseFloat(System.getProperty("upscaling.debugGlide"), 0.0f);
	private static DebugView debugView = DebugView.fromName(System.getProperty("upscaling.debugView", "off"));
	private static Upscaler upscaler = Upscaler.FSR4;

	static {
		load();
		String property = System.getProperty("upscaling.enabled");
		if (property != null) {
			enabled = Boolean.parseBoolean(property);
		}
		property = System.getProperty("upscaling.scale");
		if (property != null) {
			scalePercent = clampPercent(Math.round(parseFloat(property, 0.5f) * 100.0f));
		}
		property = System.getProperty("upscaling.upscaler");
		if (property != null) {
			upscaler = Upscaler.fromName(property, upscaler);
		}
	}

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

		static Upscaler fromName(String name, Upscaler fallback) {
			for (Upscaler value : values()) {
				if (value.name().equalsIgnoreCase(name)) {
					return value;
				}
			}
			return fallback;
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
		return scalePercent / 100.0f;
	}

	public static int scalePercent() {
		return scalePercent;
	}

	public static void setScalePercent(int percent) {
		scalePercent = clampPercent(percent);
	}

	/** Preset name for a render scale, or null when it is not one of the presets. */
	public static String presetName(int percent) {
		for (int i = 0; i < PRESET_PERCENTS.length; i++) {
			if (PRESET_PERCENTS[i] == percent) {
				return PRESET_NAMES[i];
			}
		}
		return null;
	}

	public static float sharpness() {
		return sharpnessSteps * 0.05f;
	}

	public static int sharpnessSteps() {
		return sharpnessSteps;
	}

	public static void setSharpnessSteps(int steps) {
		sharpnessSteps = Math.max(0, Math.min(20, steps));
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static void setUpscaler(Upscaler value) {
		upscaler = value;
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

	/** Steps to the next preset (the first one if the scale is custom) and returns its display name. */
	public static String cycleScale() {
		int next = 0;
		for (int i = 0; i < PRESET_PERCENTS.length; i++) {
			if (PRESET_PERCENTS[i] == scalePercent) {
				next = (i + 1) % PRESET_PERCENTS.length;
				break;
			}
		}
		scalePercent = PRESET_PERCENTS[next];
		return PRESET_NAMES[next];
	}

	private static int clampPercent(int percent) {
		return Math.max(MIN_SCALE_PERCENT, Math.min(MAX_SCALE_PERCENT, percent));
	}

	private static void load() {
		if (!Files.isRegularFile(FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			properties.load(reader);
		} catch (IOException e) {
			LOGGER.warn("Upscaling: could not read {}", FILE, e);
			return;
		}
		enabled = Boolean.parseBoolean(properties.getProperty("enabled", Boolean.toString(enabled)));
		upscaler = Upscaler.fromName(properties.getProperty("upscaler", upscaler.name()), upscaler);
		scalePercent = clampPercent(parseInt(properties.getProperty("renderScalePercent"), scalePercent));
		setSharpnessSteps(Math.round(parseFloat(properties.getProperty("sharpness"), 0.0f) / 0.05f));
	}

	/** Writes the user-facing settings to config/upscaling.properties. */
	public static void save() {
		Properties properties = new Properties();
		properties.setProperty("enabled", Boolean.toString(enabled));
		properties.setProperty("upscaler", upscaler.name());
		properties.setProperty("renderScalePercent", Integer.toString(scalePercent));
		properties.setProperty("sharpness", String.format(java.util.Locale.ROOT, "%.2f", sharpness()));
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				properties.store(writer, "Upscaling mod settings");
			}
		} catch (IOException e) {
			LOGGER.warn("Upscaling: could not write {}", FILE, e);
		}
	}

	private static int parseInt(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
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
