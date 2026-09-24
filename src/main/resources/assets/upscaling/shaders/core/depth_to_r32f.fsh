#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies the D32 depth buffer into an R32F colour image: fsr4vk accepts depth
// either as D32_S8 or as a plain R32 float image, and Minecraft has no stencil.

uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
#ifdef FORWARD_DEPTH
    // Shader-pack loaders keep forward depth; the FSR context is created for reverse-Z.
    depth = 1.0 - depth;
#endif
    fragColor = vec4(depth, 0.0, 0.0, 1.0);
}
