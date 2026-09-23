package io.github.steinkoloss.upscaling.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SkyRenderer keeps the main target it was constructed with (and is constructed
 * lazily mid-frame), so it would draw into whichever target happened to be
 * active at that moment. Resolve the target per frame instead.
 */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	@ModifyExpressionValue(
			method = "render",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")
	)
	private RenderTarget upscaling$currentMainTarget(RenderTarget cached) {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}
}
