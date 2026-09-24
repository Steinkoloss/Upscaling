#version 330
#extension GL_ARB_separate_shader_objects : require

#include <upscaling:reprojection.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D MotionSampler;
uniform sampler2D CurrColorSampler;
uniform sampler2D PrevColorSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    int mode = int(Params.x + 0.5);
    float depth = texture(DepthSampler, texCoord).r;
    vec3 scene = texture(CurrColorSampler, texCoord).rgb;

    if (mode == 1) {
        // Motion vectors: grey = still, red/green = horizontal/vertical motion.
        vec2 mv = texture(MotionSampler, texCoord).xy;
        fragColor = vec4(clamp(0.5 + mv * Params.y, 0.0, 1.0), 0.5, 1.0);
    } else if (mode == 2) {
        // Orientation check: red = below the camera, green = above, blue = sky.
        if (isSky(depth)) {
            fragColor = vec4(0.1, 0.2, 0.8, 1.0);
        } else {
            float height = cameraRelativePosition(uvToNdc(texCoord), depth).y;
            vec3 tint = height < 0.0 ? vec3(1.0, 0.15, 0.15) : vec3(0.15, 1.0, 0.15);
            fragColor = vec4(mix(scene, tint, 0.6), 1.0);
        }
    } else {
        // Reprojection error: last frame warped by the motion vectors, minus this frame.
        // Near-black means the vectors are right; bright edges are expected at disocclusions.
        vec2 mv = texture(MotionSampler, texCoord).xy;
        vec2 prevUv = texCoord + mv * 0.5;
        vec3 prev = texture(PrevColorSampler, prevUv).rgb;
        bool offscreen = any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)));
        vec3 error = offscreen ? vec3(0.0, 0.0, 0.3) : abs(scene - prev) * 4.0;
        fragColor = vec4(error, 1.0);
    }
}
