package io.github.steinkoloss.upscaling.api;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import io.github.steinkoloss.upscaling.FrameState;
import io.github.steinkoloss.upscaling.Jitter;
import io.github.steinkoloss.upscaling.MotionVectors;
import io.github.steinkoloss.upscaling.SceneTarget;
import io.github.steinkoloss.upscaling.UpscalingConfig;
import io.github.steinkoloss.upscaling.fsr.Fsr4Upscaler;
import org.slf4j.Logger;

/**
 * Entry point for shader-pack loaders that render the world at a reduced scale and upscale it
 * themselves. Vitrail looks this class up by name (it does not link against this mod) and offers
 * its scaled frame here before running its own spatial upscale.
 *
 * <p>This mod jitters the projection the loader captures for the frame and arms this class
 * ({@code GameRendererMixin}); the loader hands over the pack's final image at render
 * resolution plus the opaque depth it keeps, and this computes un-jittered camera motion
 * vectors from that depth and runs FSR 4. Nothing is done on frames that were not armed, so
 * the loader keeps its own upscale whenever FSR 4 is off or unavailable.
 */
public final class ShaderPackUpscaler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static boolean armed;
	/** Last reported sizes and result, so the log says once when the hand-off starts or changes. */
	private static String lastReport = "";

	private ShaderPackUpscaler() {
	}

	/** Set per frame by the projection hook: whether this frame's projection was jittered for FSR 4. */
	public static void arm(boolean value) {
		armed = value;
	}

	/**
	 * Upscales a shader pack's scaled frame into {@code output}.
	 *
	 * @param world        the pack's final image at render resolution
	 * @param forwardDepth the opaque world's depth at render resolution, forward (0 near, 1 far)
	 * @return true when FSR 4 wrote {@code output}; false leaves the frame to the caller
	 */
	public static boolean upscale(GpuTextureView world, GpuTextureView forwardDepth, int renderWidth, int renderHeight,
			RenderTarget output) {
		if (!armed) {
			return false;
		}
		armed = false;
		MotionVectors.compute(forwardDepth, renderWidth, renderHeight, true);
		if (UpscalingConfig.shaderPackTest()) {
			// Test aid for machines where FSR 4 cannot run: same hand-off, jitter and motion
			// vectors, with a bilinear stretch in place of FSR 4 and the debug views on top.
			SceneTarget.upscale(world, output);
			if (UpscalingConfig.debugView() != UpscalingConfig.DebugView.OFF) {
				MotionVectors.drawDebug(forwardDepth, world, output);
			}
			MotionVectors.storeHistory(world.texture(), renderWidth, renderHeight);
			FrameState.endFrame();
			report(renderWidth, renderHeight, output, true);
			return true;
		}
		boolean upscaled = Fsr4Upscaler.upscale(
				world,
				forwardDepth,
				true,
				renderWidth,
				renderHeight,
				MotionVectors.texture(),
				output,
				Jitter.x(),
				Jitter.y(),
				!FrameState.hasPrevious(),
				FrameState.frameTimeMs(),
				FrameState.verticalFov(),
				0.05f,
				FrameState.far());
		FrameState.endFrame();
		report(renderWidth, renderHeight, output, upscaled);
		return upscaled;
	}

	private static void report(int renderWidth, int renderHeight, RenderTarget output, boolean upscaled) {
		String report = renderWidth + "x" + renderHeight + " -> " + output.width + "x" + output.height
				+ (upscaled ? "" : " failed");
		if (!report.equals(lastReport)) {
			lastReport = report;
			if (upscaled) {
				LOGGER.info("Upscaling: {} upscales the shader pack's frame {}",
						UpscalingConfig.shaderPackTest() ? "the bilinear test stand-in" : "FSR 4", report);
			} else {
				LOGGER.warn("Upscaling: FSR 4 did not take the shader pack's frame {}; the pack's own upscale runs", report);
			}
		}
	}
}
