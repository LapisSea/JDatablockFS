#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

struct Rect {
    float xOff;
    float yOff;
    uint16_t letterId;
};
struct Letter {
    float16_t w, h;
    uint16_t u0, v0, u1, v1;
};
struct Uniform {
    vec2 pos;
    float scale;
    u8vec4 col;
    float outline;
    float xScale;
};

layout (binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;

layout (binding = 1) readonly buffer Uniforms { Uniform data[]; } in_ubo;

layout (binding = 2) readonly buffer Rects { Rect data[]; } in_rects;

layout (binding = 3) readonly buffer Letters { Letter data[]; } atlas;

layout (location = 0) out vec2 uvOut;
layout (location = 1) out float outline;
layout (location = 2) out float scale;
layout (location = 3) out vec4 col;

void main() {

    Uniform ubo = in_ubo.data[gl_InstanceIndex];
    Rect rect = in_rects.data[gl_VertexIndex / 6];
    Letter letter = atlas.data[rect.letterId];

    float x, y, u, v;
    int quadId = gl_VertexIndex % 6;
    if (quadId == 0 || quadId == 3) {
        x = 0; y = 0;
        u = letter.u0; v = letter.v1;
    } else if (quadId == 1) {
        x = letter.w; y = 0;
        u = letter.u1; v = letter.v1;
    } else if (quadId == 2 || quadId == 4) {
        x = letter.w; y = letter.h;
        u = letter.u1; v = letter.v0;
    } else /*if (quadId == 5)*/ {
        x = 0; y = letter.h;
        u = letter.u0; v = letter.v0;
    }

    vec2 pos = vec2(x + rect.xOff, y + rect.yOff);
    pos.x *= ubo.xScale;
    pos *= ubo.scale;
    pos += ubo.pos;

    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * vec4(pos, 0, 1.0);

    uvOut = vec2(u, v) / 65535;
    outline = ubo.outline;
    scale = ubo.scale;
    col = vec4(ubo.col) / 255;
}
