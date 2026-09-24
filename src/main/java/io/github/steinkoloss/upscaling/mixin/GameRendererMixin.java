package io.github.steinkoloss.upscaling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import io.github.steinkoloss.upscaling.FrameState;
import io.github.steinkoloss.upscaling.MotionVectors;
import io.github.steinkoloss.upscaling.SceneTarget;
import io.github.steinkoloss.upscaling.ShaderPackCompat;
import io.github.steinkoloss.upscaling.Jitter;
import io.github.steinkoloss.upscaling.UpscalingConfig;
import io.github.steinkoloss.upscaling.api.ShaderPackUpscaler;
import io.github.steinkoloss.upscaling.fsr.Fsr4Upscaler;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	@Final
	private List<PostChain> appliedPostEffects;

	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void upscaling$substituteSceneTarget(CallbackInfoReturnable<RenderTarget> cir) {
		RenderTarget scene = SceneTarget.active();
		if (scene != null) {
			cir.setReturnValue(scene);
		}
	}

	/** Records the world projection for motion vectors and applies the sub-pixel jitter. */
	@WrapOperation(
			method = "renderLevel",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
			)
	)
	private GpuBufferSlice upscaling$jitterProjection(ProjectionMatrixBuffer buffer, Matrix4f projection, Operation<GpuBufferSlice> original) {
		CameraRenderState camera = this.gameRenderState.levelRenderState.cameraRenderState;
		if (this.upscaling$shaderPackFsrThisFrame()) {
			// A shader-pack loader (Vitrail) has already shrunk the main target for its render
			// scale and captures the matrix passed here as the pack's projection: jitter it, and
			// let ShaderPackUpscaler run FSR 4 when the loader offers its scaled frame.
			Matrix4f rendered = FrameState.beginFrame(
					projection,
					camera,
					this.mainRenderTarget.width,
					this.mainRenderTarget.height,
					Minecraft.getInstance().getWindow().getWidth(),
					true);
			ShaderPackUpscaler.arm(true);
			return original.call(buffer, rendered);
		}
		ShaderPackUpscaler.arm(false);
		if (!this.upscaling$scaledThisFrame()) {
			FrameState.reset();
			return original.call(buffer, projection);
		}
		float scale = UpscalingConfig.scale();
		Matrix4f rendered = FrameState.beginFrame(
				projection,
				camera,
				SceneTarget.renderSize(this.mainRenderTarget.width, scale),
				SceneTarget.renderSize(this.mainRenderTarget.height, scale),
				this.mainRenderTarget.width,
				UpscalingConfig.jitter() || Fsr4Upscaler.usable(this.mainRenderTarget.width, this.mainRenderTarget.height)
		);
		return original.call(buffer, rendered);
	}

	/**
	 * Renders only the world at reduced resolution. The hand, screen effects,
	 * post effects and GUI still draw at full resolution on top of the upscaled image.
	 */
	@WrapOperation(
			method = "renderLevel",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"
			)
	)
	private void upscaling$renderWorldScaled(LevelRenderer levelRenderer, GraphicsResourceAllocator allocator, boolean renderOutline,
			CameraRenderState camera, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldCreateBossFog, boolean consistentDepthRequired,
			Operation<Void> original) {
		if (!this.upscaling$scaledThisFrame()) {
			original.call(levelRenderer, allocator, renderOutline, camera, terrainFog, fogColor, shouldCreateBossFog, consistentDepthRequired);
			return;
		}

		SceneTarget.begin(this.mainRenderTarget.width, this.mainRenderTarget.height, UpscalingConfig.scale());
		RenderTarget scene;
		try {
			original.call(levelRenderer, allocator, renderOutline, camera, terrainFog, fogColor, shouldCreateBossFog, consistentDepthRequired);
		} finally {
			scene = SceneTarget.end();
		}

		MotionVectors.compute(scene);
		boolean upscaled = false;
		if (Fsr4Upscaler.usable(this.mainRenderTarget.width, this.mainRenderTarget.height)) {
			upscaled = Fsr4Upscaler.upscale(
					scene,
					MotionVectors.texture(),
					this.mainRenderTarget,
					Jitter.x(),
					Jitter.y(),
					!FrameState.hasPrevious(),
					FrameState.frameTimeMs(),
					FrameState.verticalFov(),
					0.05f,
					camera.depthFar
			);
		}
		if (!upscaled) {
			SceneTarget.upscale(scene, this.mainRenderTarget);
		}
		if (UpscalingConfig.debugView() != UpscalingConfig.DebugView.OFF) {
			MotionVectors.drawDebug(scene, this.mainRenderTarget);
		}
		MotionVectors.storeHistory(scene);
		FrameState.endFrame();
	}

	/**
	 * Post effects sample the main depth buffer at full resolution, and shader packs run their own
	 * chain on window-sized targets; leave those frames native.
	 */
	@Unique
	private boolean upscaling$scaledThisFrame() {
		return UpscalingConfig.enabled() && this.appliedPostEffects.isEmpty() && !ShaderPackCompat.shaderPackActive();
	}

	/**
	 * Whether FSR 4 should upscale a shader pack's frame: a Vitrail pack is drawing, Vitrail's own
	 * render scale has shrunk the main target below the window, and FSR 4 is selected and usable.
	 */
	@Unique
	private boolean upscaling$shaderPackFsrThisFrame() {
		if (!UpscalingConfig.enabled() || UpscalingConfig.upscaler() != UpscalingConfig.Upscaler.FSR4
				|| !ShaderPackCompat.shaderPackActive()) {
			return false;
		}
		var window = Minecraft.getInstance().getWindow();
		return this.mainRenderTarget.width < window.getWidth()
				&& (UpscalingConfig.shaderPackTest() || Fsr4Upscaler.usable(window.getWidth(), window.getHeight()));
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void upscaling$close(CallbackInfo ci) {
		SceneTarget.close();
		MotionVectors.close();
		Fsr4Upscaler.close();
	}
}
