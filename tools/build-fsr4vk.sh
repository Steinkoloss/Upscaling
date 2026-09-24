#!/usr/bin/env bash
# Builds fsr4vk (https://github.com/dvj5411/fsr4vk) as a native Linux library and
# assembles the shader/model asset tree it loads at runtime. Nothing from fsr4vk
# is committed to this repository or bundled in the mod jar.
#
#   tools/build-fsr4vk.sh [output-dir]      # default: build/fsr4vk
#
# Output:
#   <output-dir>/libfsr4vk.so     the FidelityFX-API provider (ffxCreateContext etc.)
#   <output-dir>/assets/          general/ (with .portable.spv fallbacks), colors/, fsr411/
#
# Point the mod at it with -Dupscaling.fsr4vk=<output-dir>.
#
# Needs: git, g++ (C++20), and the Vulkan loader (libvulkan.so.1). Vulkan
# headers are fetched if the system has none.
set -euo pipefail

FSR4VK_REPO="https://github.com/dvj5411/fsr4vk"
# v0.4.1 (2026-09-22). Bump deliberately: the asset layout is tied to the source.
FSR4VK_COMMIT="2ce6063"
VULKAN_HEADERS_REPO="https://github.com/KhronosGroup/Vulkan-Headers"
VULKAN_HEADERS_TAG="v1.4.328"

cd "$(dirname "$0")/.."
OUT="$(realpath -m "${1:-build/fsr4vk}")"
WORK="build/fsr4vk-work"
mkdir -p "$WORK" "$OUT"

if [ ! -d "$WORK/fsr4vk/.git" ]; then
	git clone --quiet "$FSR4VK_REPO" "$WORK/fsr4vk"
fi
git -C "$WORK/fsr4vk" fetch --quiet origin
git -C "$WORK/fsr4vk" checkout --quiet "$FSR4VK_COMMIT"

VULKAN_INCLUDE=/usr/include
if [ ! -f /usr/include/vulkan/vulkan.h ] || ! grep -q VK_EXT_descriptor_buffer /usr/include/vulkan/vulkan_core.h 2>/dev/null; then
	if [ ! -d "$WORK/Vulkan-Headers" ]; then
		git clone --quiet --depth 1 --branch "$VULKAN_HEADERS_TAG" "$VULKAN_HEADERS_REPO" "$WORK/Vulkan-Headers"
	fi
	VULKAN_INCLUDE="$WORK/Vulkan-Headers/include"
fi

LIBVULKAN="$(ldconfig -p | awk '/libvulkan\.so\.1 .*x86-64/ {print $NF; exit}')"
if [ -z "$LIBVULKAN" ]; then
	echo "libvulkan.so.1 not found; install your distro's Vulkan loader" >&2
	exit 1
fi

SRC="$WORK/fsr4vk"
echo "Compiling libfsr4vk.so ..."
g++ -std=c++20 -O2 -fPIC -shared \
	-I"$VULKAN_INCLUDE" -I"$SRC/amd-fidelityfx-sdk/Kits/FidelityFX/api/include" \
	"$SRC/provider/ffx_vk_provider.cpp" "$LIBVULKAN" \
	-Wl,-soname,libfsr4vk.so -o "$OUT/libfsr4vk.so"

# The Windows build embeds these; on Linux the provider reads the same tree from
# FSR4_VK_ASSET_ROOT. Portable (no mixed-dot extension) shaders sit next to the
# native ones as pass-NN.portable.spv, exactly as the embedded index names them.
echo "Assembling assets ..."
rm -rf "$OUT/assets"
mkdir -p "$OUT/assets"
cp -r "$SRC/assets/general" "$OUT/assets/general"
(cd "$SRC/assets/portable" && find . -name '*.spv') | while read -r f; do
	cp "$SRC/assets/portable/$f" "$OUT/assets/general/${f%.spv}.portable.spv"
done
cp -r "$SRC/assets/colors" "$OUT/assets/colors"
cp -r "$SRC/assets/fsr411" "$OUT/assets/fsr411"
cp "$SRC/LICENSES/AMD-FidelityFX-SDK-MIT.md" "$SRC/LICENSES/GPL-3.0.txt" "$OUT/"

echo "Done: $OUT"
nm -D --defined-only "$OUT/libfsr4vk.so" | grep -E ' ffx(Create|Destroy|Configure|Query|Dispatch)' || true
