#version 460

struct Rect {
    float xOff, yOff;
    int letterId;
};
struct Letter {
    float w, h;
    float u0, v0, u1, v1;
};

layout (binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;

layout (binding = 1) readonly uniform Uniforms {
    mat4 modelMat;
    vec4 col;
} ubo;

layout (binding = 2) readonly buffer Rects { Rect data[]; } in_rects;

layout (binding = 3) readonly buffer Letters { Letter data[]; } atlas;

layout (location = 0) out vec2 uvOut;
layout (location = 1) out float drawSize;

void main() {

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

    mat4 modelMat = ubo.modelMat;
    vec4 r0 = modelMat[1];
    float scalingFactor = sqrt(r0.x * r0.x + r0.y * r0.y + r0.z * r0.z);

    x += rect.xOff;
    y += rect.yOff;

    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * modelMat * vec4(x, y, 0, 1.0);

    uvOut = vec2(u, v);
    drawSize = scalingFactor;
}
