package io.github.steinkoloss.upscaling.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import io.github.steinkoloss.upscaling.fsr.Fsr4DeviceFeatures;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Asks Minecraft to enable the device extensions and features FSR 4 (fsr4vk)
 * needs. Optional feature sets are enabled only when the GPU supports all of
 * them, so unsupported hardware just runs without FSR 4.
 */
@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void upscaling$addFsr4FeatureSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		cir.getReturnValue().add(Fsr4DeviceFeatures.REQUIRED);
		cir.getReturnValue().add(Fsr4DeviceFeatures.MIXED_DOT_FAST_PATH);
	}
}
