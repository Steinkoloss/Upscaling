#ifndef UPSCALING_REPROJECTION_GLSL
#define UPSCALING_REPROJECTION_GLSL

// Camera matrices for reprojecting a pixel of the current frame into the previous
// one. ViewProj = Projection * ViewRotation; positions are relative to the camera.
layout(std140) uniform Reprojection {
    mat4 CurrInvViewProjJittered;
    mat4 CurrViewProj;
    mat4 PrevViewProj;
    vec4 CameraDelta;   // xyz: current minus previous camera position
    vec4 Params;        // x: debug mode, y: motion vector display gain
};

// Minecraft uses reverse Z: the far plane (and the cleared sky) is depth 0.
bool isSky(float depth) {
    return depth <= 0.0;
}

// Screen UV to NDC. If this ever disagrees with the rasteriser the orientation
// debug view shows it: ground must reconstruct below the camera.
vec2 uvToNdc(vec2 uv) {
    return uv * 2.0 - 1.0;
}

vec3 cameraRelativePosition(vec2 ndc, float depth) {
    vec4 p = CurrInvViewProjJittered * vec4(ndc, depth, 1.0);
    return p.xyz / p.w;
}

// NDC offset from this pixel to where the same surface was last frame, without jitter.
vec2 motionVectorNdc(vec2 ndc, float depth) {
    vec3 rel = cameraRelativePosition(ndc, depth);
    vec4 curr = CurrViewProj * vec4(rel, 1.0);
    // The sky is infinitely far away, so only camera rotation moves it.
    vec3 prevRel = isSky(depth) ? rel : rel + CameraDelta.xyz;
    vec4 prev = PrevViewProj * vec4(prevRel, 1.0);
    return prev.xy / prev.w - curr.xy / curr.w;
}

#endif
