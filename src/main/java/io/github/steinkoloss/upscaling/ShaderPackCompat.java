package io.github.steinkoloss.upscaling;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Stays out of the way of shader-pack loaders. Vitrail draws the world through its
 * own chain of window-sized targets and reads the game's main target while doing
 * so; handing it the reduced-size scene target instead produces garbage. Vitrail
 * has its own render scale, so while it draws a pack this mod renders natively.
 *
 * <p>Looked up by reflection so Vitrail stays an optional, undeclared dependency.
 */
public final class ShaderPackCompat {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final @Nullable MethodHandle VITRAIL_DRAWING_PACK = lookup("dev.vitrail.render.PackChain", "drawingPack");
	private static boolean lastState;

	private ShaderPackCompat() {
	}

	/** Whether a shader pack is currently drawing the world. */
	public static boolean shaderPackActive() {
		boolean active = false;
		if (VITRAIL_DRAWING_PACK != null) {
			try {
				active = (boolean) VITRAIL_DRAWING_PACK.invokeExact();
			} catch (Throwable t) {
				active = false;
			}
		}
		if (active != lastState) {
			lastState = active;
			LOGGER.info("Upscaling: {}", active
					? "a Vitrail shader pack is drawing; rendering natively (use Vitrail's own render scale)"
					: "no shader pack is drawing; upscaling applies again");
		}
		return active;
	}

	private static @Nullable MethodHandle lookup(String className, String method) {
		try {
			Class<?> owner = Class.forName(className, false, ShaderPackCompat.class.getClassLoader());
			return MethodHandles.publicLookup().findStatic(owner, method, MethodType.methodType(boolean.class));
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}
}
