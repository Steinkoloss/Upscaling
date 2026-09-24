#version 330
#extension GL_ARB_separate_shader_objects : require

// Robust Contrast-Adaptive Sharpening (RCAS), ported from AMD FidelityFX
// Super Resolution 1 (ffx_fsr1.h, MIT license, Copyright (c) AMD). fsr4vk has no
// internal sharpening, so the host applies RCAS to the upscaled output, as the
// FSR 3.1 API does when enableSharpening is set. Noise removal is omitted.

uniform sampler2D InSampler;

layout(std140) uniform RcasInfo {
    vec4 RcasParams; // x: sharpness scale, exp2(-stops)
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))

vec3 load(ivec2 p, ivec2 size) {
    // Input may be RGBA16F; RCAS expects [0, 1].
    return clamp(texelFetch(InSampler, clamp(p, ivec2(0), size - 1), 0).rgb, 0.0, 1.0);
}

float rcasMax3(float a, float b, float c) { return max(a, max(b, c)); }

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 ip = ivec2(texCoord * vec2(size));
    //    b
    //  d e f
    //    h
    vec3 b = load(ip + ivec2(0, -1), size);
    vec3 d = load(ip + ivec2(-1, 0), size);
    vec3 e = load(ip, size);
    vec3 f = load(ip + ivec2(1, 0), size);
    vec3 h = load(ip + ivec2(0, 1), size);

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec2 peakC = vec2(1.0, -4.0);
    vec3 hitMin = min(mn4, e) / (4.0 * mx4 + 1.0e-5);
    vec3 hitMax = (peakC.x - max(mx4, e)) / (4.0 * mn4 + peakC.y);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-FSR_RCAS_LIMIT, min(rcasMax3(lobeRGB.r, lobeRGB.g, lobeRGB.b), 0.0)) * RcasParams.x;
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 color = (lobe * (b + d + f + h) + e) * rcpL;
    fragColor = vec4(color, 1.0);
}
