package io.github.steinkoloss.upscaling.fsr;

import com.mojang.logging.LogUtils;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * Minimal Java 25 FFM binding for AMD's FidelityFX API as exported by fsr4vk
 * ({@code ffxCreateContext}, {@code ffxDestroyContext}, {@code ffxDispatch}).
 *
 * <p>Struct offsets and constants were taken from the SDK headers bundled with
 * fsr4vk (ffx_api.h, ffx_api_types.h, ffx_upscale.h) by compiling a program that
 * prints {@code sizeof}/{@code offsetof} on x86-64 Linux; see tools/ffx-layout.cpp.
 */
public final class FfxApi {
	private static final Logger LOGGER = LogUtils.getLogger();

	// Return codes.
	public static final int RETURN_OK = 0;

	// Descriptor types.
	static final long CREATE_CONTEXT_DESC_TYPE_UPSCALE = 0x00010000L;
	static final long DISPATCH_DESC_TYPE_UPSCALE = 0x00010001L;
	static final long DESC_TYPE_OVERRIDE_VERSION = 5L;
	/** fsr4vk's Vulkan backend descriptor (CreateBackendVkDesc in ffx_vk_provider.cpp). */
	static final long DESC_TYPE_BACKEND_VK = 3L;
	/** fsr4vk extension carrying the device's Vulkan API version ("FSR4VKAP"). */
	static final long DESC_TYPE_FSR4_VULKAN_API_VERSION = 0x46535234564b4150L;

	// Context creation flags.
	static final int ENABLE_DEPTH_INVERTED = 1 << 3;
	static final int ENABLE_NON_LINEAR_COLORSPACE = 1 << 8;
	// Dispatch flags.
	static final int FLAG_NON_LINEAR_COLOR_SRGB = 1 << 1;

	// Resources.
	static final int RESOURCE_TYPE_TEXTURE2D = 2;
	static final int FORMAT_R16G16B16A16_FLOAT = 4;
	static final int FORMAT_R16G16_FLOAT = 18;
	static final int FORMAT_R32_FLOAT = 28;
	static final int USAGE_READ_ONLY = 0;
	static final int USAGE_UAV = 1 << 1;
	static final int STATE_UNORDERED_ACCESS = 1 << 1;
	static final int STATE_COMPUTE_READ = 1 << 2;

	// fsr4vk provider version ids: 0xF5A5CA1E << 32 | major << 22 | minor << 12 | patch.
	public static final long VERSION_FSR_4_0_2 = (0xF5A5CA1EL << 32) | ((4L << 22) | (0L << 12) | 2L);
	public static final long VERSION_FSR_4_1_1 = (0xF5A5CA1EL << 32) | ((4L << 22) | (1L << 12) | 1L);

	// Struct sizes/offsets (x86-64).
	static final long HEADER_SIZE = 16;
	static final long CREATE_UPSCALE_SIZE = 48;
	static final long BACKEND_VK_SIZE = 40;
	static final long API_VERSION_DESC_SIZE = 24;
	static final long OVERRIDE_VERSION_SIZE = 24;
	static final long RESOURCE_SIZE = 48;
	static final long DISPATCH_UPSCALE_SIZE = 432;

	private final MethodHandle createContext;
	private final MethodHandle destroyContext;
	private final MethodHandle dispatch;
	private final MemorySegment messageCallback;

	private FfxApi(SymbolLookup library) {
		Linker linker = Linker.nativeLinker();
		this.createContext = linker.downcallHandle(
				library.find("ffxCreateContext").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.destroyContext = linker.downcallHandle(
				library.find("ffxDestroyContext").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.dispatch = linker.downcallHandle(
				library.find("ffxDispatch").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		try {
			MethodHandle onMessage = MethodHandles.lookup().findStatic(
					FfxApi.class, "onMessage", MethodType.methodType(void.class, int.class, MemorySegment.class));
			this.messageCallback = linker.upcallStub(
					onMessage, FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Arena.global());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Loads fsr4vk. The provider reads its shader/model tree from the
	 * FSR4_VK_ASSET_ROOT environment variable, so that is set in this process first.
	 */
	public static FfxApi load(Path library, Path assetRoot, boolean providerLog) {
		setenv("FSR4_VK_ASSET_ROOT", assetRoot.toAbsolutePath().toString());
		if (providerLog) {
			setenv("FSR4_VK_LOG", "1");
			setenv("FSR4_VK_LOG_PATH", assetRoot.toAbsolutePath().resolveSibling("provider.log").toString());
		}
		return new FfxApi(SymbolLookup.libraryLookup(library, Arena.global()));
	}

	/** Creates an upscale context; returns the context handle or null (the provider logs why). */
	public MemorySegment createUpscaleContext(Arena arena, long vkDevice, long vkPhysicalDevice, long vkGetDeviceProcAddr,
			int vulkanApiVersion, long providerVersion, int flags, int maxRenderWidth, int maxRenderHeight, int maxUpscaleWidth,
			int maxUpscaleHeight) {
		MemorySegment override = arena.allocate(OVERRIDE_VERSION_SIZE, 8);
		override.set(ValueLayout.JAVA_LONG, 0, DESC_TYPE_OVERRIDE_VERSION);
		override.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
		override.set(ValueLayout.JAVA_LONG, 16, providerVersion);

		MemorySegment apiVersion = arena.allocate(API_VERSION_DESC_SIZE, 8);
		apiVersion.set(ValueLayout.JAVA_LONG, 0, DESC_TYPE_FSR4_VULKAN_API_VERSION);
		apiVersion.set(ValueLayout.ADDRESS, 8, override);
		apiVersion.set(ValueLayout.JAVA_INT, 16, vulkanApiVersion);

		MemorySegment backend = arena.allocate(BACKEND_VK_SIZE, 8);
		backend.set(ValueLayout.JAVA_LONG, 0, DESC_TYPE_BACKEND_VK);
		backend.set(ValueLayout.ADDRESS, 8, apiVersion);
		backend.set(ValueLayout.JAVA_LONG, 16, vkDevice);
		backend.set(ValueLayout.JAVA_LONG, 24, vkPhysicalDevice);
		backend.set(ValueLayout.JAVA_LONG, 32, vkGetDeviceProcAddr);

		MemorySegment create = arena.allocate(CREATE_UPSCALE_SIZE, 8);
		create.set(ValueLayout.JAVA_LONG, 0, CREATE_CONTEXT_DESC_TYPE_UPSCALE);
		create.set(ValueLayout.ADDRESS, 8, backend);
		create.set(ValueLayout.JAVA_INT, 16, flags);
		create.set(ValueLayout.JAVA_INT, 20, maxRenderWidth);
		create.set(ValueLayout.JAVA_INT, 24, maxRenderHeight);
		create.set(ValueLayout.JAVA_INT, 28, maxUpscaleWidth);
		create.set(ValueLayout.JAVA_INT, 32, maxUpscaleHeight);
		create.set(ValueLayout.ADDRESS, 40, this.messageCallback);

		MemorySegment context = arena.allocate(ValueLayout.ADDRESS);
		int result = invoke(this.createContext, context, create, MemorySegment.NULL);
		if (result != RETURN_OK) {
			LOGGER.warn("Upscaling: ffxCreateContext failed with code {}", result);
			return null;
		}
		return context;
	}

	public void destroyContext(MemorySegment context) {
		invoke(this.destroyContext, context, MemorySegment.NULL);
	}

	public int dispatch(MemorySegment context, MemorySegment dispatchDesc) {
		return invoke(this.dispatch, context, dispatchDesc);
	}

	/** Writes an FfxApiResource describing a single-mip 2D Vulkan image. */
	static void writeResource(MemorySegment desc, long offset, long vkImage, int format, int width, int height, int usage, int state) {
		desc.set(ValueLayout.JAVA_LONG, offset, vkImage);
		desc.set(ValueLayout.JAVA_INT, offset + 8, RESOURCE_TYPE_TEXTURE2D);
		desc.set(ValueLayout.JAVA_INT, offset + 12, format);
		desc.set(ValueLayout.JAVA_INT, offset + 16, width);
		desc.set(ValueLayout.JAVA_INT, offset + 20, height);
		desc.set(ValueLayout.JAVA_INT, offset + 24, 1); // depth
		desc.set(ValueLayout.JAVA_INT, offset + 28, 1); // mipCount
		desc.set(ValueLayout.JAVA_INT, offset + 32, 0); // flags
		desc.set(ValueLayout.JAVA_INT, offset + 36, usage);
		desc.set(ValueLayout.JAVA_INT, offset + 40, state);
	}

	private static int invoke(MethodHandle handle, Object... args) {
		try {
			return (int) handle.invokeWithArguments(args);
		} catch (Throwable t) {
			throw new IllegalStateException("FidelityFX call failed", t);
		}
	}

	/** fpMessage: provider diagnostics as a wchar_t string (UTF-32 on Linux). */
	private static void onMessage(int type, MemorySegment text) {
		MemorySegment chars = text.reinterpret(Integer.MAX_VALUE);
		StringBuilder message = new StringBuilder();
		for (long i = 0; i < 4096; i++) {
			int codePoint = chars.get(ValueLayout.JAVA_INT, i * 4);
			if (codePoint == 0) {
				break;
			}
			message.appendCodePoint(codePoint);
		}
		if (type == 0) {
			LOGGER.error("Upscaling/fsr4vk: {}", message);
		} else {
			LOGGER.warn("Upscaling/fsr4vk: {}", message);
		}
	}

	private static void setenv(String name, String value) {
		Linker linker = Linker.nativeLinker();
		MethodHandle setenv = linker.downcallHandle(
				linker.defaultLookup().find("setenv").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		try (Arena arena = Arena.ofConfined()) {
			int result = (int) setenv.invokeExact(arena.allocateFrom(name), arena.allocateFrom(value), 1);
			if (result != 0) {
				throw new IllegalStateException("setenv(" + name + ") failed");
			}
		} catch (Throwable t) {
			throw new IllegalStateException("setenv(" + name + ") failed", t);
		}
	}
}
