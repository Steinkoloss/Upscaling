# Upscaling

A Fabric mod that renders Minecraft's world at a lower internal resolution and
upscales it. The goal is temporal upscaling (FSR 3.1, FSR 4 via
[fsr4vk](https://github.com/dvj5411/fsr4vk)) on Minecraft's Vulkan renderer,
on Linux first.

## Status: spike (step 2 of the plan)

What works today, on Minecraft 26.3 with Fabric:

- The world renders into a reduced-resolution target and is stretched back to
  the window with a plain bilinear filter. The hand, screen effects, post
  effects and GUI still draw at full resolution on top.
- `F8` toggles upscaling on/off, `F9` cycles render scale
  (Quality 67% / Balanced 59% / Performance 50% / Ultra Performance 33%).
- JVM flags: `-Dupscaling.scale=0.5`, `-Dupscaling.enabled=false`.

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
   upscale. **Done (this spike).**
3. Sub-pixel camera jitter + motion vectors (camera-only first, from depth
   reprojection), with a debug view.
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
a world from `run/saves/` and takes screenshots between keybind presses, so
changes can be checked without a monitor or GPU.

## License

AGPL-3.0. See `LICENSE`.
