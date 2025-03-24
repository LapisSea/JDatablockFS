#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

layout (constant_id = 0) const float distanceRange = 1;
layout (constant_id = 1) const float size = 1;

layout (location = 0) out vec4 out_color;

layout (location = 0) in vec2 uv;

layout (binding = 1) readonly uniform Uniforms {
    vec2 pos;
    float scale;
    u8vec4 col;
    float outline;
} ubo;

layout (binding = 4) uniform sampler2D msdf;


float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float screenPxRange(float drawSize) {
    return (drawSize / size) * distanceRange;
}
float sampleMsdf(vec2 uv, float outline, float drawSize) {
    vec3 msd = texture(msdf, uv).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    if (outline > 0) {
        float mid = 0.5;
        if (sd > mid) {
            float off = sd - mid;
            sd = mid - off;
        }
        sd += outline / screenPxRange(drawSize) / 2;
    }
    float screenPxDistance = screenPxRange(drawSize) * (sd - 0.5);
    float dist = screenPxDistance + 0.5;
    float opacity = clamp(dist, 0.0, 1.0);
    return opacity;
}

void main() {
    float opacity = sampleMsdf(uv, ubo.outline, ubo.scale);
    if (opacity < 1.0 / 256)discard;
    vec4 col = vec4(ubo.col) / 255;
    out_color = vec4(col.rgb, col.a * opacity);
}

