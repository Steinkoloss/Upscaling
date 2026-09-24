package io.github.steinkoloss.upscaling.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderInvoker {
	/** The frame's current command buffer (begun on demand). Must not be called inside a render pass. */
	@Invoker("commandBuffer")
	VkCommandBuffer upscaling$commandBuffer();
}
