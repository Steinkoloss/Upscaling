package io.github.steinkoloss.upscaling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import io.github.steinkoloss.upscaling.FrameState;
import io.github.steinkoloss.upscaling.MotionVectors;
import io.github.steinkoloss.upscaling.SceneTarget;
import io.github.steinkoloss.upscaling.Jitter;
import io.github.steinkoloss.upscaling.UpscalingConfig;
import io.github.steinkoloss.upscaling.fsr.Fsr4Upscaler;
import java.util.List;
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
		if (!this.upscaling$scaledThisFrame()) {
			FrameState.reset();
			return original.call(buffer, projection);
		}
		CameraRenderState camera = this.gameRenderState.levelRenderState.cameraRenderState;
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

	/** Post effects sample the main depth buffer at full resolution; leave those frames native for now. */
	@Unique
	private boolean upscaling$scaledThisFrame() {
		return UpscalingConfig.enabled() && this.appliedPostEffects.isEmpty();
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void upscaling$close(CallbackInfo ci) {
		SceneTarget.close();
		MotionVectors.close();
		Fsr4Upscaler.close();
	}
}
