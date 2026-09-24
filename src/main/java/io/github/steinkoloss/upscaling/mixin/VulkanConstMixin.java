package io.github.steinkoloss.upscaling.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import io.github.steinkoloss.upscaling.fsr.Fsr4Upscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Minecraft has no storage-image usage flag; FSR 4 writes its output as one. */
@Mixin(VulkanConst.class)
abstract class VulkanConstMixin {
	private static final int VK_IMAGE_USAGE_STORAGE_BIT = 0x8;

	@ModifyReturnValue(method = "textureUsageToVk", at = @At("RETURN"))
	private static int upscaling$storageUsage(int vkUsage, int usage, GpuFormat format) {
		return (usage & Fsr4Upscaler.USAGE_STORAGE) != 0 ? vkUsage | VK_IMAGE_USAGE_STORAGE_BIT : vkUsage;
	}
}
