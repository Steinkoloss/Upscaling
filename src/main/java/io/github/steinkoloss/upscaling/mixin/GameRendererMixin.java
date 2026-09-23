package io.github.steinkoloss.upscaling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.steinkoloss.upscaling.SceneTarget;
import io.github.steinkoloss.upscaling.UpscalingConfig;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void upscaling$substituteSceneTarget(CallbackInfoReturnable<RenderTarget> cir) {
		RenderTarget scene = SceneTarget.active();
		if (scene != null) {
			cir.setReturnValue(scene);
		}
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
	private void upscaling$renderWorldScaled(LevelRenderer levelRenderer, com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
			boolean renderOutline, net.minecraft.client.renderer.state.level.CameraRenderState camera,
			com.mojang.renderpearl.api.buffers.GpuBufferSlice terrainFog, org.joml.Vector4f fogColor, boolean shouldCreateBossFog,
			boolean consistentDepthRequired, Operation<Void> original) {
		// Post effects sample the main depth buffer at full resolution; leave those frames native for now.
		if (!UpscalingConfig.enabled() || consistentDepthRequired) {
			original.call(levelRenderer, allocator, renderOutline, camera, terrainFog, fogColor, shouldCreateBossFog, consistentDepthRequired);
			return;
		}

		SceneTarget.begin(this.mainRenderTarget.width, this.mainRenderTarget.height, UpscalingConfig.scale());
		try {
			original.call(levelRenderer, allocator, renderOutline, camera, terrainFog, fogColor, shouldCreateBossFog, consistentDepthRequired);
		} finally {
			SceneTarget.endAndUpscale(this.mainRenderTarget);
		}
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void upscaling$close(CallbackInfo ci) {
		SceneTarget.close();
	}
}
