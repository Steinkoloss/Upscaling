# Upscaling

A Fabric mod that renders Minecraft's world at a lower internal resolution and
upscales it. The goal is temporal upscaling (FSR 3.1, FSR 4 via
[fsr4vk](https://github.com/dvj5411/fsr4vk)) on Minecraft's Vulkan renderer,
on Linux first.

## Status: step 5 of the plan (FSR 4 wired in, awaiting a real-GPU test)

On Minecraft 26.3 (including pre-releases) with Fabric, Vulkan backend:

- The world renders at a reduced internal resolution; hand, screen effects,
  post effects and GUI stay at full resolution on top.
- FSR 4 through [fsr4vk](https://github.com/dvj5411/fsr4vk), called directly
  from Java (FFM) on Minecraft's own Vulkan device and command buffer. No
  OptiScaler, no Proton. Falls back to a bilinear stretch whenever FSR 4 is
  unavailable (not installed, missing GPU features, OpenGL backend, software
  Vulkan, output above 3840x2160) or fails at runtime.
- Camera motion vectors reconstructed from depth, sub-pixel jitter (on
  automatically when FSR 4 runs). Moving objects (mobs, particles, water) only
  get the camera's motion so far.
- Works alongside Sodium 0.9.3-alpha.1 and Distant Horizons 3.3.2.

**What has and has not been verified.** Everything was built and run headless
on Mesa's lavapipe (software Vulkan): the device features get enabled, fsr4vk
loads, the FSR 4.1.1 context is created with the expected flags and sizes, and
the first dispatch is recorded with the expected inputs. Lavapipe then crashes
executing fsr4vk's shaders (fsr4vk's own smoke test crashes the same way), so
**no FSR 4 frame has been seen yet**. That needs a real GPU. Things most likely
to need fixing on first real run: jitter direction, colour handling, image
barriers.

### Keys

| Key | Action |
|---|---|
| F7 | Cycle upscaler (FSR 4 / Bilinear) |
| F8 | Upscaling on/off (off = native rendering) |
| F9 | Cycle render scale: Native AA 100% / Quality 67% / Balanced 59% / Performance 50% / Ultra Performance 33% |
| F10 | Cycle debug views: motion vectors / orientation / reprojection error |

### Installing FSR 4 (Linux)

fsr4vk is not bundled. Build it once with the script in this repo (needs git,
g++ and the Vulkan loader):

```sh
git clone -b claude/minecraft-fsr4-dlss4-0dmwcw https://github.com/Steinkoloss/Upscaling
cd Upscaling
tools/build-fsr4vk.sh <game dir>/upscaling/fsr4vk
```

`<game dir>` is the folder with `mods/` and `saves/` (e.g. `~/.minecraft`, or
the instance's `minecraft/` folder in Prism). Then put the mod jar in `mods/`
with Fabric API, set **Video Settings → Graphics API → Vulkan**, and restart.
`logs/latest.log` says which upscaler is active (search for `Upscaling`).

### JVM flags

`-Dupscaling.scale=1.0` initial render scale (0.25..1.0, default 0.5),
`-Dupscaling.enabled=false`, `-Dupscaling.upscaler=fsr4|bilinear`,
`-Dupscaling.fsr4vk=<dir>` (default `<game dir>/upscaling/fsr4vk`),
`-Dupscaling.fsr4.version=4.1.1|4.0.2`, `-Dupscaling.fsr4.log=true` (fsr4vk
writes `provider.log` next to its assets), `-Dupscaling.jitter=true`,
`-Dupscaling.debugView=motion|orientation|reprojection`, and test aids
`-Dupscaling.debugSpin=30`, `-Dupscaling.debugGlide=4`,
`-Dupscaling.fsr4.allowSoftware=true`.

## Plan

1. Project setup. Done.
2. Hook the Vulkan renderer, render the world at reduced resolution, bilinear
   upscale. **Done.**
3. Sub-pixel camera jitter + motion vectors (camera-only first, from depth
   reprojection), with debug views. **Done.**
4. fsr4vk built as a native Linux `.so` and called through the FidelityFX API;
   its required Vulkan device features enabled through `VulkanFeatureSets`.
   Its shader and model files are not bundled. **Done, untested on real GPU.**
5. Validate and tune on real hardware (RX 9070 XT / RADV first).
   FSR 3.1 as a fallback for GPUs fsr4vk can't run on.
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
