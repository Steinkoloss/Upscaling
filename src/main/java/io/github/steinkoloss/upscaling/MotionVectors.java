package io.github.steinkoloss.upscaling;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Camera-only motion vectors reconstructed from the scene depth buffer, plus the
 * debug views used to check them. Moving objects (entities, particles, water) are
 * not covered yet; they get the camera's motion.
 *
 * <p>Vectors are stored in an RG16F target at render resolution as the NDC offset
 * from the current pixel to its position in the previous frame, without jitter.
 */
public final class MotionVectors {
	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putMat4f().putVec4().putVec4().get();

	private static final BindGroupLayout REPROJECTION = BindGroupLayout.builder()
			.withUniform("Reprojection", UniformType.UNIFORM_BUFFER)
			.build();
	private static final BindGroupLayout DEPTH = BindGroupLayout.builder()
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final BindGroupLayout DEBUG_SAMPLERS = BindGroupLayout.builder()
			.withUniform("MotionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CurrColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("PrevColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();

	private static final RenderPipeline MOTION_PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("upscaling", "pipeline/motion_vectors"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("upscaling", "core/motion_vectors"))
			.withBindGroupLayout(REPROJECTION)
			.withBindGroupLayout(DEPTH)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
	private static final RenderPipeline DEBUG_PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("upscaling", "pipeline/debug_view"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("upscaling", "core/debug_view"))
			.withBindGroupLayout(REPROJECTION)
			.withBindGroupLayout(DEPTH)
			.withBindGroupLayout(DEBUG_SAMPLERS)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	private static @Nullable GpuBuffer ubo;
	private static @Nullable GpuTexture motionTexture;
	private static @Nullable GpuTextureView motionView;
	private static @Nullable GpuTexture historyTexture;
	private static @Nullable GpuTextureView historyView;

	private MotionVectors() {
	}

	/** Writes camera motion vectors for {@code scene} (which must hold this frame's depth). */
	public static void compute(RenderTarget scene) {
		compute(scene.getDepthTextureView(), scene.width, scene.height, false);
	}

	/**
	 * Writes camera motion vectors from a depth image at render resolution.
	 *
	 * @param forwardDepth true when the image holds forward depth (0 near, 1 far), as shader-pack
	 *                     loaders keep it; false for the game's reverse-Z depth buffer
	 */
	public static void compute(GpuTextureView depth, int width, int height, boolean forwardDepth) {
		ensureResources(width, height);
		uploadUniforms(UpscalingConfig.debugView().ordinal(), forwardDepth);

		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Upscaling motion vectors", motionView, Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(MOTION_PIPELINE));
			pass.setUniform("Reprojection", ubo);
			pass.setUniform("DepthSampler", depth, nearest());
			pass.draw(3, 1, 0, 0);
		}
	}

	/** This frame's motion vectors (RG16F, render resolution); valid after {@link #compute}. */
	public static GpuTexture texture() {
		return motionTexture;
	}

	/** Draws the selected debug view over {@code output}. */
	public static void drawDebug(RenderTarget scene, RenderTarget output) {
		drawDebug(scene.getDepthTextureView(), scene.getColorTextureView(), output);
	}

	/** Draws the selected debug view over {@code output}, from any depth and colour image at render resolution. */
	public static void drawDebug(GpuTextureView depth, GpuTextureView color, RenderTarget output) {
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Upscaling debug view", output.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(DEBUG_PIPELINE));
			pass.setUniform("Reprojection", ubo);
			pass.setUniform("DepthSampler", depth, nearest());
			pass.setUniform("MotionSampler", motionView, nearest());
			pass.setUniform("CurrColorSampler", color, linear());
			pass.setUniform("PrevColorSampler", historyView, linear());
			pass.draw(3, 1, 0, 0);
		}
	}

	/** Keeps this frame's scene colour for the reprojection-error view. */
	public static void storeHistory(RenderTarget scene) {
		storeHistory(scene.getColorTexture(), scene.width, scene.height);
	}

	public static void storeHistory(GpuTexture color, int width, int height) {
		RenderSystem.getDevice()
				.createCommandEncoder()
				.copyTextureToTexture(color, historyTexture, 0, 0, 0, 0, 0, width, height);
	}

	public static void close() {
		if (motionTexture != null) {
			motionView.close();
			motionTexture.close();
			historyView.close();
			historyTexture.close();
			motionTexture = null;
			historyTexture = null;
		}
		if (ubo != null) {
			ubo.close();
			ubo = null;
		}
	}

	private static void ensureResources(int width, int height) {
		GpuDevice device = RenderSystem.getDevice();
		if (ubo == null) {
			ubo = device.createBuffer(() -> "Upscaling reprojection UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
		}
		if (motionTexture != null && motionTexture.getWidth(0) == width && motionTexture.getHeight(0) == height) {
			return;
		}
		if (motionTexture != null) {
			motionView.close();
			motionTexture.close();
			historyView.close();
			historyTexture.close();
		}
		// Usage 15: copy src/dst, sampled, render attachment (same as Minecraft's own targets).
		motionTexture = device.createTexture(() -> "Upscaling motion vectors", 15, GpuFormat.RG16_FLOAT, width, height, 1, 1);
		motionView = device.createTextureView(motionTexture);
		historyTexture = device.createTexture(() -> "Upscaling history", 15, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		historyView = device.createTextureView(historyTexture);
		FrameState.reset();
	}

	private static void uploadUniforms(int debugMode, boolean forwardDepth) {
		Vec3 delta = FrameState.cameraDelta();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
					.putMat4f(FrameState.currViewProjJittered().invert(new org.joml.Matrix4f()))
					.putMat4f(FrameState.currViewProj())
					.putMat4f(FrameState.prevViewProj())
					.putVec4((float) delta.x, (float) delta.y, (float) delta.z, 0.0f)
					.putVec4(debugMode, 20.0f, forwardDepth ? 1.0f : 0.0f, 0.0f)
					.get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), data);
		}
	}

	private static GpuSampler nearest() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
	}

	private static GpuSampler linear() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}
}
