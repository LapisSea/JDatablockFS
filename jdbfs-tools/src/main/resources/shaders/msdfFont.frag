#version 460

layout (constant_id = 0) const float distanceRange = 1;
layout (constant_id = 1) const float size = 1;

layout (location = 0) out vec4 out_color;

layout (location = 0) in vec2 uv;
layout (location = 1) in float drawSize;

layout (binding = 1) readonly uniform Uniforms {
    mat4 projectionMat;
    vec4 col;
} ubo;

layout (binding = 4) uniform sampler2D msdf;


float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float screenPxRange() {
    return (drawSize / size) * distanceRange;
}
float sampleMsdf(vec2 uv) {
    vec3 msd = texture(msdf, uv).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    //    if (outline) {
    //        float mid = 0.5;
    //        if (sd > mid) {
    //            float off = sd - mid;
    //            sd = mid - off;
    //        }
    //        sd += 6 / drawSize;
    //    }
    float screenPxDistance = screenPxRange() * (sd - 0.5);
    float dist = screenPxDistance + 0.5;
    float opacity = clamp(dist, 0.0, 1.0);
    return opacity;
}

void main() {
    float opacity = sampleMsdf(uv);
    if (opacity < 1.0 / 256)discard;
    vec4 col = ubo.col;
    out_color = vec4(col.rgb, col.a * opacity);
}

