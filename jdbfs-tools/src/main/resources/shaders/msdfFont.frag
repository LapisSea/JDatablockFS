#version 460

layout (constant_id = 0) const float distanceRange = 1;
layout (constant_id = 1) const float size = 1;
layout (constant_id = 2) const bool BuiltInAA = false;


layout (set = 0, binding = 1) uniform sampler2D msdf;

layout (location = 0) out vec4 out_color;
layout (location = 0) in vec2 uv;
layout (location = 1) in float outline;
layout (location = 2) in float scale;
layout (location = 3) in vec4 col;

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
    float opacity = sampleMsdf(uv, outline, scale);
    if (BuiltInAA) {
        if (opacity < 1.0 / 256)discard;
        out_color = vec4(col.rgb, col.a * opacity);
    } else {
        if (opacity < 0.5)discard;
        out_color = col;
    }
}

