package io.github.steinkoloss.upscaling;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

/**
 * The reduced-resolution target the world is rendered into while upscaling is
 * active, plus the pass that scales it back up to the window-sized main target.
 *
 * <p>While {@link #active()} returns a target, {@code GameRenderer#mainRenderTarget()}
 * hands that target out instead of the real one, so {@code LevelRenderer} and
 * everything it sizes from the main target (frame-graph transients, OIT targets)
 * render at the reduced size without further patching.
 */
public final class SceneTarget {
	private static @Nullable TextureTarget target;
	private static boolean active;

	private SceneTarget() {
	}

	/** The target to substitute for the main render target, or null when not rendering the world scaled. */
	public static @Nullable RenderTarget active() {
		return active ? target : null;
	}

	/** Render-resolution size of one dimension at the given scale. */
	public static int renderSize(int outputSize, float scale) {
		return Math.max(1, Math.round(outputSize * scale));
	}

	/** Sizes the scene target for this frame and starts redirecting the main target to it. */
	public static void begin(int outputWidth, int outputHeight, float scale) {
		int width = renderSize(outputWidth, scale);
		int height = renderSize(outputHeight, scale);
		if (target == null) {
			// Same formats as MainTarget so every world pipeline stays compatible.
			target = new TextureTarget("Upscaling Scene", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		} else if (target.width != width || target.height != height) {
			target.resize(width, height);
		}
		active = true;
	}

	/** Stops redirecting the main target and returns the scene that was rendered. */
	public static RenderTarget end() {
		active = false;
		return target;
	}

	/** Upscales the scene colour into {@code output}. */
	public static void upscale(RenderTarget scene, RenderTarget output) {
		upscale(scene.getColorTextureView(), output);
	}

	/** Bilinear-stretches {@code color} over {@code output}. */
	public static void upscale(com.mojang.renderpearl.api.textures.GpuTextureView color, RenderTarget output) {
		// Spike: plain bilinear stretch. This is the slot a temporal upscaler replaces.
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Upscaling bilinear", output.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", color, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}

	public static void close() {
		if (target != null) {
			target.destroyBuffers();
			target = null;
		}
		active = false;
	}
}
