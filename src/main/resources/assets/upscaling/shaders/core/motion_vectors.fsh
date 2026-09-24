#version 330
#extension GL_ARB_separate_shader_objects : require

#include <upscaling:reprojection.glsl>

uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = deviceDepth(texture(DepthSampler, texCoord).r);
    fragColor = vec4(motionVectorNdc(uvToNdc(texCoord), depth), 0.0, 1.0);
}
