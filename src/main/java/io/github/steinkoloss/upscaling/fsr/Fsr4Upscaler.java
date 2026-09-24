package io.github.steinkoloss.upscaling.fsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import io.github.steinkoloss.upscaling.UpscalingConfig;
import io.github.steinkoloss.upscaling.mixin.FrontendGpuDeviceAccessor;
import io.github.steinkoloss.upscaling.mixin.VulkanCommandEncoderInvoker;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.slf4j.Logger;

/**
 * FSR 4 through fsr4vk on Minecraft's Vulkan backend.
 *
 * <p>Per frame: the scene colour (RGBA8, sRGB-encoded) is copied into an RGBA16F
 * image and the D32 depth into an R32F image, because those are the formats
 * fsr4vk accepts. Then, directly in Minecraft's current command buffer, the inputs
 * go GENERAL -> SHADER_READ_ONLY_OPTIMAL (fsr4vk samples them in that layout;
 * Minecraft keeps every image in GENERAL), fsr4vk records its dispatch, the inputs
 * go back to GENERAL, and the RGBA16F output is blitted into the main target.
 */
public final class Fsr4Upscaler {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** Private texture usage bit, turned into VK_IMAGE_USAGE_STORAGE_BIT by VulkanConstMixin. */
	public static final int USAGE_STORAGE = 1 << 12;
	private static final int USAGE_DEFAULT = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
			| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

	/** fsr4vk's supported range (native/resolution-plan.hpp). */
	private static final int MIN_SIZE = 64;
	private static final int MAX_WIDTH = 3840;
	private static final int MAX_HEIGHT = 2160;

	private static final int VK_API_VERSION_1_2 = (1 << 22) | (2 << 12);
	private static final int VK_IMAGE_LAYOUT_GENERAL = 1;
	private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;
	private static final int VK_IMAGE_ASPECT_COLOR_BIT = 1;
	private static final long VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT = 0x10000L;
	private static final long VK_ACCESS_2_MEMORY_READ_BIT = 0x8000L;
	private static final long VK_ACCESS_2_MEMORY_WRITE_BIT = 0x10000L;
	private static final int VK_QUEUE_FAMILY_IGNORED = ~0;

	private static final BindGroupLayout IN_SAMPLER = BindGroupLayout.builder()
			.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final BindGroupLayout DEPTH_SAMPLER = BindGroupLayout.builder()
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final RenderPipeline COLOR_TO_RGBA16F = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("upscaling", "pipeline/color_to_rgba16f"))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(IN_SAMPLER)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
	private static final RenderPipeline DEPTH_TO_R32F = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("upscaling", "pipeline/depth_to_r32f"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("upscaling", "core/depth_to_r32f"))
			.withBindGroupLayout(DEPTH_SAMPLER)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	private enum State {
		UNINITIALIZED,
		READY,
		FAILED
	}

	private static State state = State.UNINITIALIZED;
	private static @Nullable FfxApi api;
	private static @Nullable VulkanDevice device;

	private static @Nullable Arena contextArena;
	private static @Nullable MemorySegment context;
	private static int contextWidth;
	private static int contextHeight;

	private static @Nullable Image color;
	private static @Nullable Image depth;
	private static @Nullable Image output;
	private static @Nullable Image exposure;

	private record Image(GpuTexture texture, GpuTextureView view) {
		long vkImage() {
			return ((VulkanGpuTexture) this.texture).vkImage();
		}

		void close() {
			this.view.close();
			this.texture.close();
		}
	}

	private Fsr4Upscaler() {
	}

	/** Whether FSR 4 is selected, set up and able to upscale to this output size. Initialises lazily. */
	public static boolean usable(int outputWidth, int outputHeight) {
		if (UpscalingConfig.upscaler() != UpscalingConfig.Upscaler.FSR4) {
			return false;
		}
		if (outputWidth < MIN_SIZE || outputHeight < MIN_SIZE || outputWidth > MAX_WIDTH || outputHeight > MAX_HEIGHT) {
			return false;
		}
		if (state == State.UNINITIALIZED) {
			initialize();
		}
		return state == State.READY;
	}

	/**
	 * Upscales {@code scene} into {@code output} with FSR 4. Returns false (after
	 * disabling FSR 4) if anything fails, so the caller can fall back.
	 */
	public static boolean upscale(RenderTarget scene, GpuTexture motionVectors, RenderTarget target, float jitterX, float jitterY,
			boolean reset, float frameTimeMs, float verticalFov, float near, float far) {
		int rw = scene.width;
		int rh = scene.height;
		int ow = target.width;
		int oh = target.height;
		if (rw < MIN_SIZE || rh < MIN_SIZE) {
			return false;
		}
		try {
			boolean newContext = ensureContext(ow, oh);
			ensureImages(rw, rh, ow, oh);
			convertInputs(scene);
			if (!dispatch(motionVectors, rw, rh, ow, oh, jitterX, jitterY, reset || newContext, frameTimeMs, verticalFov, near, far)) {
				fail("dispatch was rejected (see the fsr4vk messages above)");
				return false;
			}
			blit(output.view(), target);
			return true;
		} catch (RuntimeException e) {
			LOGGER.error("Upscaling: FSR 4 failed", e);
			fail(e.getMessage());
			return false;
		}
	}

	public static void close() {
		destroyContext();
		closeImages();
		if (exposure != null) {
			exposure.close();
			exposure = null;
		}
	}

	private static void initialize() {
		state = State.FAILED;
		GpuDevice gpuDevice = RenderSystem.getDevice();
		if (!(gpuDevice instanceof FrontendGpuDevice frontend)
				|| !(((FrontendGpuDeviceAccessor) frontend).upscaling$backend() instanceof VulkanDevice vulkan)) {
			LOGGER.info("Upscaling: FSR 4 needs the Vulkan backend; using bilinear");
			return;
		}
		if (gpuDevice.getDeviceInfo().type() == DeviceType.CPU && !UpscalingConfig.fsr4AllowSoftware()) {
			// Mesa's lavapipe crashes executing fsr4vk's shaders (seen with fsr4vk's own smoke test too).
			LOGGER.info("Upscaling: FSR 4 is disabled on software Vulkan ({}); using bilinear", gpuDevice.getDeviceInfo().name());
			return;
		}
		if (!Fsr4DeviceFeatures.enabledOnCurrentDevice()) {
			LOGGER.info("Upscaling: this GPU/driver lacks the Vulkan features FSR 4 needs; using bilinear");
			return;
		}
		Path dir = UpscalingConfig.fsr4vkDirectory();
		Path library = dir.resolve("libfsr4vk.so");
		Path assets = dir.resolve("assets");
		if (!Files.isRegularFile(library) || !Files.isDirectory(assets.resolve("general"))) {
			LOGGER.info("Upscaling: fsr4vk not found in {} (build it with tools/build-fsr4vk.sh); using bilinear", dir);
			return;
		}
		try {
			api = FfxApi.load(library, assets, UpscalingConfig.fsr4ProviderLog());
			device = vulkan;
			state = State.READY;
			LOGGER.info("Upscaling: loaded fsr4vk from {}", dir);
		} catch (RuntimeException | LinkageError e) {
			LOGGER.error("Upscaling: could not load {}; using bilinear", library, e);
		}
	}

	private static void fail(String reason) {
		LOGGER.warn("Upscaling: disabling FSR 4 for this session: {}", reason);
		state = State.FAILED;
		destroyContext();
	}

	private static boolean ensureContext(int ow, int oh) {
		if (context != null && contextWidth == ow && contextHeight == oh) {
			return false;
		}
		destroyContext();
		VkDevice vkDevice = device.vkDevice();
		contextArena = Arena.ofShared();
		context = api.createUpscaleContext(
				contextArena,
				vkDevice.address(),
				vkDevice.getPhysicalDevice().address(),
				getDeviceProcAddr(vkDevice),
				VK_API_VERSION_1_2,
				UpscalingConfig.fsr4Version(),
				FfxApi.ENABLE_DEPTH_INVERTED | FfxApi.ENABLE_NON_LINEAR_COLORSPACE,
				// Render size never exceeds the output size.
				ow, oh, ow, oh);
		if (context == null) {
			contextArena.close();
			contextArena = null;
			throw new IllegalStateException("ffxCreateContext failed");
		}
		contextWidth = ow;
		contextHeight = oh;
		return true;
	}

	private static long getDeviceProcAddr(VkDevice vkDevice) {
		long address = VK10.vkGetInstanceProcAddr(vkDevice.getPhysicalDevice().getInstance(), "vkGetDeviceProcAddr");
		if (address == 0L) {
			throw new IllegalStateException("vkGetDeviceProcAddr not found");
		}
		return address;
	}

	private static void destroyContext() {
		if (context != null) {
			// The provider waits for its own in-flight work; make sure ours is submitted first.
			api.destroyContext(context);
			context = null;
		}
		if (contextArena != null) {
			contextArena.close();
			contextArena = null;
		}
	}

	private static void ensureImages(int rw, int rh, int ow, int oh) {
		GpuDevice gpu = RenderSystem.getDevice();
		if (color == null || color.texture().getWidth(0) != rw || color.texture().getHeight(0) != rh) {
			if (color != null) {
				color.close();
				depth.close();
			}
			color = image(gpu, "FSR color", USAGE_DEFAULT, GpuFormat.RGBA16_FLOAT, rw, rh);
			depth = image(gpu, "FSR depth", USAGE_DEFAULT, GpuFormat.R32_FLOAT, rw, rh);
		}
		if (output == null || output.texture().getWidth(0) != ow || output.texture().getHeight(0) != oh) {
			if (output != null) {
				output.close();
			}
			output = image(gpu, "FSR output", USAGE_DEFAULT | USAGE_STORAGE, GpuFormat.RGBA16_FLOAT, ow, oh);
		}
		if (exposure == null) {
			// External exposure of 1.0: the input is display-referred SDR.
			exposure = image(gpu, "FSR exposure", USAGE_DEFAULT, GpuFormat.R32_FLOAT, 1, 1);
			ByteBuffer one = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putFloat(0, 1.0f);
			gpu.createCommandEncoder().writeToTexture(exposure.texture(), one, 0, 0, 0, 0, 1, 1);
		}
	}

	private static void closeImages() {
		if (color != null) {
			color.close();
			depth.close();
			color = null;
			depth = null;
		}
		if (output != null) {
			output.close();
			output = null;
		}
	}

	private static Image image(GpuDevice gpu, String label, int usage, GpuFormat format, int width, int height) {
		GpuTexture texture = gpu.createTexture(() -> "Upscaling " + label, usage, format, width, height, 1, 1);
		return new Image(texture, gpu.createTextureView(texture));
	}

	private static void convertInputs(RenderTarget scene) {
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(() -> "Upscaling FSR color input", color.view(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(COLOR_TO_RGBA16F));
			pass.setUniform("InSampler", scene.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "Upscaling FSR depth input", depth.view(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(DEPTH_TO_R32F));
			pass.setUniform("DepthSampler", scene.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static boolean dispatch(GpuTexture motionVectors, int rw, int rh, int ow, int oh, float jitterX, float jitterY,
			boolean reset, float frameTimeMs, float verticalFov, float near, float far) {
		long motionImage = ((VulkanGpuTexture) motionVectors).vkImage();
		long[] inputs = {color.vkImage(), depth.vkImage(), motionImage, exposure.vkImage()};
		VkCommandBuffer commandBuffer = ((VulkanCommandEncoderInvoker) device.createCommandEncoder()).upscaling$commandBuffer();

		transition(commandBuffer, inputs, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		int result;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment desc = arena.allocate(FfxApi.DISPATCH_UPSCALE_SIZE, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, FfxApi.DISPATCH_DESC_TYPE_UPSCALE);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_LONG, 16, commandBuffer.address());
			FfxApi.writeResource(desc, 24, inputs[0], FfxApi.FORMAT_R16G16B16A16_FLOAT, rw, rh, FfxApi.USAGE_READ_ONLY, FfxApi.STATE_COMPUTE_READ);
			FfxApi.writeResource(desc, 72, inputs[1], FfxApi.FORMAT_R32_FLOAT, rw, rh, FfxApi.USAGE_READ_ONLY, FfxApi.STATE_COMPUTE_READ);
			FfxApi.writeResource(desc, 120, inputs[2], FfxApi.FORMAT_R16G16_FLOAT, rw, rh, FfxApi.USAGE_READ_ONLY, FfxApi.STATE_COMPUTE_READ);
			FfxApi.writeResource(desc, 168, inputs[3], FfxApi.FORMAT_R32_FLOAT, 1, 1, FfxApi.USAGE_READ_ONLY, FfxApi.STATE_COMPUTE_READ);
			// 216 reactive, 264 transparencyAndComposition: left null (fsr4vk ignores them).
			FfxApi.writeResource(desc, 312, output.vkImage(), FfxApi.FORMAT_R16G16B16A16_FLOAT, ow, oh, FfxApi.USAGE_UAV, FfxApi.STATE_UNORDERED_ACCESS);
			desc.set(ValueLayout.JAVA_FLOAT, 360, jitterX);
			desc.set(ValueLayout.JAVA_FLOAT, 364, jitterY);
			// Our vectors are NDC offsets; half of that is the offset in UV, which the scale converts to pixels.
			desc.set(ValueLayout.JAVA_FLOAT, 368, 0.5f * rw);
			desc.set(ValueLayout.JAVA_FLOAT, 372, 0.5f * rh);
			desc.set(ValueLayout.JAVA_INT, 376, rw);
			desc.set(ValueLayout.JAVA_INT, 380, rh);
			desc.set(ValueLayout.JAVA_INT, 384, ow);
			desc.set(ValueLayout.JAVA_INT, 388, oh);
			desc.set(ValueLayout.JAVA_BYTE, 392, (byte) 0); // enableSharpening
			desc.set(ValueLayout.JAVA_FLOAT, 396, 0.0f); // sharpness
			desc.set(ValueLayout.JAVA_FLOAT, 400, frameTimeMs);
			desc.set(ValueLayout.JAVA_FLOAT, 404, 1.0f); // preExposure
			desc.set(ValueLayout.JAVA_BYTE, 408, (byte) (reset ? 1 : 0));
			desc.set(ValueLayout.JAVA_FLOAT, 412, near);
			desc.set(ValueLayout.JAVA_FLOAT, 416, far);
			desc.set(ValueLayout.JAVA_FLOAT, 420, verticalFov);
			desc.set(ValueLayout.JAVA_FLOAT, 424, 1.0f); // viewSpaceToMetersFactor: one block is a metre
			desc.set(ValueLayout.JAVA_INT, 428, FfxApi.FLAG_NON_LINEAR_COLOR_SRGB);
			result = api.dispatch(context, desc);
		} finally {
			transition(commandBuffer, inputs, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
		}
		return result == FfxApi.RETURN_OK;
	}

	/** Full barrier (matching Minecraft's own) plus layout transitions for {@code images}. */
	private static void transition(VkCommandBuffer commandBuffer, long[] images, int oldLayout, int newLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack).sType$Default();
			memory.srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
					.srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
					.dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
					.dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
			VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
			for (int i = 0; i < images.length; i++) {
				barriers.get(i)
						.sType$Default()
						.srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
						.srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
						.dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
						.dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
						.oldLayout(oldLayout)
						.newLayout(newLayout)
						.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
						.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
						.image(images[i])
						.subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
			}
			VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
					.sType$Default()
					.pMemoryBarriers(memory)
					.pImageMemoryBarriers(barriers);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
		}
	}

	private static void blit(GpuTextureView source, RenderTarget target) {
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Upscaling FSR output", target.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}
}
