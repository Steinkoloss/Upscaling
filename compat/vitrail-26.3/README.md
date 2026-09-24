# Vitrail on Minecraft 26.3 (Fabric): patches

[Vitrail](https://github.com/avpbynf/Vitrail-Shaders) (LGPL-3.0) runs OptiFine-format
shader packs on Minecraft's Vulkan renderer. Upstream targets 26.2. A community pull
request ([avpbynf/Vitrail-Shaders#422](https://github.com/avpbynf/Vitrail-Shaders/pull/422),
branch `main` of [BuaichiZY/Vitrail-Shaders](https://github.com/BuaichiZY/Vitrail-Shaders),
commit `016b7f2`) ports the shared code and NeoForge to 26.3. These patches go on top of it:

1. **Port the Fabric module to 26.3.** The PR left it on 26.2: three mixins target
   methods whose signatures changed or that were split, and `fabric.mod.json` refused 26.3.
2. **Declare lifted uniforms under the name the pack wrote.** The PR renamed uniforms
   after pack macros defined later in the file, which broke every Complementary
   Reimagined program that includes `common.glsl` (`nightVision`), so the pack was refused.
3. **Prepare Sodium's draw batches for the light's render lists.** Terrain cast no
   shadows: Sodium 26.3 builds draw commands in `prepareChunkRendering`, and the shadow
   walk's list swap cleared them, so the shadow map only held entities.
4. **Map locations for Vitrail's own passes outside `pack/`.** The render scale's
   and the Distant Horizons occlusion shaders didn't compile on 26.3 ("SPIR-V
   requires location for user input/output"), so any render scale below 100 %
   was dropped for the session.
5. **Offer the scaled world to an external temporal upscaler.** Before its FSR 1
   passes, the render scale hands its frame and depth to this mod's
   `ShaderPackUpscaler` (looked up by name, so neither jar needs the other).
   With FSR 4 available, it takes over the upscale.

Apply and build:

```sh
git clone https://github.com/BuaichiZY/Vitrail-Shaders && cd Vitrail-Shaders
git checkout 016b7f2
git am /path/to/compat/vitrail-26.3/*.patch
./gradlew build -x test --max-workers=1   # fabric/build/libs/vitrail-fabric-*.jar
```

Checked headless on Mesa lavapipe with Sodium 0.9.2 for 26.3 and Complementary
Reimagined r5.9.3: every program compiles, the pack draws, and a controlled superflat
scene matches Vitrail 0.11.0-beta on 26.2 (shadows included). Not checked on NeoForge.
These are unofficial; the maintainer has said they will do the 26.3 port themselves.
