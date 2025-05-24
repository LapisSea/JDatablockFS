#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

#include <vec4_u8>

struct Rect {
    float xOff;
    float yOff;
    uint letterId;
};
#ifdef NO_ARITHMETIC_TYPES
    struct Letter {
        uint wh;
        uint u0v0, u1v1;
    };
	vec2 wh(Letter value){return unpackHalf2x16(value.wh);}
	uint u0(Letter value){return value.u0v0 & 0xFFFFu;}
	uint v0(Letter value){return (value.u0v0 >> 16) & 0xFFFFu;}
	uint u1(Letter value){return value.u1v1 & 0xFFFFu;}
	uint v1(Letter value){return (value.u1v1 >> 16) & 0xFFFFu;}
#else
    struct Letter {
        float16_t w, h;
        uint16_t u0, v0, u1, v1;
    };
	vec2 wh(Letter value){return vec2(value.w, value.h);}
	uint u0(Letter value){return value.u0;}
	uint v0(Letter value){return value.v0;}
	uint u1(Letter value){return value.u1;}
	uint v1(Letter value){return value.v1;}
#endif

struct Uniform {
    vec2 pos;
    float scale;
    vec4_u8 col;
    float outline;
    float xScale;
};

layout (set = 0, binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;

layout (set = 1, binding = 0) readonly buffer Letters { Letter data[]; } atlas;

layout (set = 2, binding = 0) readonly buffer Uniforms { Uniform data[]; } in_ubo;

layout (set = 2, binding = 1) readonly buffer Rects { Rect data[]; } in_rects;

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
        u = u0(letter); v = v1(letter);
    } else if (quadId == 1) {
        x = wh(letter).x; y = 0;
        u = u1(letter); v = v1(letter);
    } else if (quadId == 2 || quadId == 4) {
        vec2 wh=wh(letter);
        x = wh.x; y = wh.y;
        u = u1(letter); v = v0(letter);
    } else /*if (quadId == 5)*/ {
        vec2 wh=wh(letter);
        x = 0; y = wh.y;
        u = u0(letter); v = v0(letter);
    }

    vec2 pos = vec2(x + rect.xOff, y + rect.yOff);
    pos.x *= ubo.xScale;
    pos *= ubo.scale;
    pos += ubo.pos;

    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * vec4(pos, 0, 1.0);

    uvOut = vec2(u, v) / 65535;
    outline = ubo.outline;
    scale = ubo.scale;
    col = toVec4(ubo.col);
}
