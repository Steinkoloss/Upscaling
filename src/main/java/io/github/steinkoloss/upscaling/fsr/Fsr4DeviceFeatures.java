package io.github.steinkoloss.upscaling.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorBufferFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderFloatControls2FeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderIntegerDotProductFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderMixedFloatDotProductFeaturesVALVE;

/**
 * The Vulkan device features fsr4vk requires, mirroring the checks in its
 * provider/vulkan_device_features.hpp for a Vulkan 1.2 device (Minecraft creates
 * its instance at 1.2, so 1.3 features come from their KHR extensions).
 *
 * <p>Features promoted to core 1.2 are requested through Minecraft's own
 * VkPhysicalDeviceVulkan12Features struct: chaining the individual 1.2 structs
 * next to it would be invalid.
 */
public final class Fsr4DeviceFeatures {
	private static final VulkanPNextStruct INTEGER_DOT = new VulkanPNextStruct(VkPhysicalDeviceShaderIntegerDotProductFeatures.class);
	private static final VulkanPNextStruct MUTABLE_DESCRIPTOR = new VulkanPNextStruct(VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.class);
	private static final VulkanPNextStruct DESCRIPTOR_BUFFER = new VulkanPNextStruct(VkPhysicalDeviceDescriptorBufferFeaturesEXT.class);
	private static final VulkanPNextStruct COMPUTE_DERIVATIVES = new VulkanPNextStruct(VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR.class);
	private static final VulkanPNextStruct MIXED_DOT = new VulkanPNextStruct(VkPhysicalDeviceShaderMixedFloatDotProductFeaturesVALVE.class);
	private static final VulkanPNextStruct FLOAT_CONTROLS_2 = new VulkanPNextStruct(VkPhysicalDeviceShaderFloatControls2FeaturesKHR.class);

	public static final String REQUIRED_NAME = "Upscaling: FSR 4";

	public static final FeatureSet REQUIRED = new FeatureSet(
			REQUIRED_NAME,
			Set.of(
					"VK_KHR_shader_integer_dot_product",
					"VK_EXT_mutable_descriptor_type",
					"VK_EXT_descriptor_buffer",
					"VK_KHR_compute_shader_derivatives"
			),
			Set.of(
					new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt16"),
					new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageReadWithoutFormat"),
					new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderInt8"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "storageBuffer8BitAccess"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "runtimeDescriptorArray"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "descriptorBindingVariableDescriptorCount"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "vulkanMemoryModel"),
					new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "vulkanMemoryModelDeviceScope"),
					new VulkanFeature(VulkanFeatureSets.SYNC2_FEATURES_STRUCT, "synchronization2"),
					new VulkanFeature(INTEGER_DOT, "shaderIntegerDotProduct"),
					new VulkanFeature(MUTABLE_DESCRIPTOR, "mutableDescriptorType"),
					new VulkanFeature(DESCRIPTOR_BUFFER, "descriptorBuffer"),
					new VulkanFeature(COMPUTE_DERIVATIVES, "computeDerivativeGroupLinear")
			)
	);

	/**
	 * fsr4vk switches to its faster shaders whenever the GPU supports this
	 * extension (it checks physical support, not what was enabled), so it must be
	 * enabled whenever it is supported. RADV exposes it; lavapipe does not.
	 */
	public static final FeatureSet MIXED_DOT_FAST_PATH = new FeatureSet(
			"Upscaling: FSR 4 mixed-dot fast path",
			Set.of("VK_VALVE_shader_mixed_float_dot_product", "VK_KHR_shader_float_controls2"),
			Set.of(
					new VulkanFeature(MIXED_DOT, "shaderMixedFloatDotProductFloat16AccFloat32"),
					new VulkanFeature(FLOAT_CONTROLS_2, "shaderFloatControls2")
			)
	);

	private Fsr4DeviceFeatures() {
	}

	/** Whether Minecraft's device was created with everything FSR 4 needs. */
	public static boolean enabledOnCurrentDevice() {
		// DeviceInfo lists enabled device extensions with a " (D)" suffix.
		Set<String> extensions = RenderSystem.getDevice().getDeviceInfo().underlyingExtensions();
		for (String extension : REQUIRED.extensions()) {
			if (!extensions.contains(extension + " (D)")) {
				return false;
			}
		}
		return true;
	}
}
