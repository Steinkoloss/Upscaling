# Upscaling

A Fabric mod that renders Minecraft's world at a lower internal resolution and
upscales it. The goal is temporal upscaling (FSR 3.1, FSR 4 via
[fsr4vk](https://github.com/dvj5411/fsr4vk)) on Minecraft's Vulkan renderer,
on Linux first.

## Status: step 3 of the plan (jitter + camera motion vectors)

What works today, on Minecraft 26.3 (including pre-releases) with Fabric:

- The world renders into a reduced-resolution target and is stretched back to
  the window with a plain bilinear filter. The hand, screen effects, post
  effects and GUI still draw at full resolution on top.
- `F8` toggles upscaling on/off, `F9` cycles render scale
  (Quality 67% / Balanced 59% / Performance 50% / Ultra Performance 33%).
- Camera motion vectors reconstructed from the depth buffer every frame
  (RG16F, render resolution, NDC offset to the previous frame, jitter excluded).
  They cover camera rotation and movement; moving objects just get the camera's
  motion for now.
- Sub-pixel camera jitter (Halton 2,3, phase count scaled with the upscale ratio),
  off by default until a temporal upscaler consumes it.
- `F10` cycles debug views: motion vectors, orientation (reconstructed height:
  red below the camera, green above, blue sky) and reprojection error (last
  frame warped by the motion vectors minus this frame; near-black means right).
- JVM flags: `-Dupscaling.scale=0.5`, `-Dupscaling.enabled=false`,
  `-Dupscaling.jitter=true`, `-Dupscaling.debugView=motion|orientation|reprojection`,
  and two test aids: `-Dupscaling.debugSpin=30` (turn the camera, degrees/second)
  and `-Dupscaling.debugGlide=4` (slide back and forth over spawn, blocks/second).
- Works alongside Sodium 0.9.3-alpha.1 and Distant Horizons 3.3.2 on the
  Vulkan backend (DH's LODs render into the reduced-resolution target too).

This is a bilinear stretch, not an upscaler: it looks worse than native by
design. It proves the hook points and gives the temporal upscaler a slot.

Known gaps in the spike:

- Frames with an active post effect (spectating a creeper etc.) fall back to
  native, because post effects read the full-resolution depth buffer.
- Entity outlines (glowing effect) are still sized to the window and have not
  been checked at reduced scale.
- Line rendering (block outline, debug lines) uses the window size for line
  width, so lines are thicker than they should be at low scale.

Verified only headless, on Mesa's lavapipe software Vulkan driver. Not yet run on
real GPU hardware.

## Plan

1. Project setup. Done.
2. Hook the Vulkan renderer, render the world at reduced resolution, bilinear
   upscale. **Done.**
3. Sub-pixel camera jitter + motion vectors (camera-only first, from depth
   reprojection), with debug views. **Done.**
4. FSR 3.1 backend through AMD's FidelityFX API (native library, called with
   Minecraft's Vulkan handles).
5. fsr4vk built as a native Linux `.so`, swapped in behind the same API; enable
   its required Vulkan device features through `VulkanFeatureSets`. Its shader
   and model files are not bundled; the user points the mod at them.
6. Per-object motion vectors (entities, particles, water, clouds), reactive and
   transparency masks.
7. More Minecraft versions. 26.2 has a different Vulkan backend
   (`com.mojang.blaze3d.vulkan` instead of `com.mojang.renderpearl`), so it
   needs its own hooks.

## Build

Needs JDK 25.

```sh
./gradlew build            # mod jar in build/libs/
./gradlew runClient        # dev client
```

`tools/headless-screenshots.sh` boots the dev client on a virtual display, joins
a world from `run/saves/` (reset from `run/pristine-saves/` each run) and takes
screenshots between keybind presses, so changes can be checked without a monitor
or GPU. Example, checking motion vectors while the camera turns:

```sh
CLIENT_JVM_ARGS="-Dupscaling.debugSpin=30 -Dupscaling.debugView=reprojection" \
  tools/headless-screenshots.sh testworld F2 F10 F10 F2
```

## License

AGPL-3.0. See `LICENSE`.
