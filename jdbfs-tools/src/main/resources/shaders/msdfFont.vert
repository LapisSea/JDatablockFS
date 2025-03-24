#version 460

struct Rect {
    float x0, y0, x1, y1;
    float u0, v0, u1, v1;
};


layout (binding = 1) readonly uniform Uniforms {
    mat4 modelMat;
    mat4 projectionMat;
    vec4 col;
    float drawSize;
} ubo;

layout (binding = 0) readonly buffer Rects { Rect data[]; } in_rects;

layout (location = 0) out vec4 colOut;
layout (location = 1) out vec2 uvOut;
layout (location = 2) out float drawSize;

void main() {

    Rect rect = in_rects.data[gl_VertexIndex / 6];

    float x, y, u, v;
    int quadId = gl_VertexIndex % 6;
    if (quadId == 0 || quadId == 3) {
        x = rect.x0; y = rect.y0;
        u = rect.u0; v = rect.v1;
    } else if (quadId == 1) {
        x = rect.x1; y = rect.y0;
        u = rect.u1; v = rect.v1;
    } else if (quadId == 2 || quadId == 4) {
        x = rect.x1; y = rect.y1;
        u = rect.u1; v = rect.v0;
    } else /*if (quadId == 5)*/ {
        x = rect.x0; y = rect.y1;
        u = rect.u0; v = rect.v0;
    }

    gl_Position = ubo.projectionMat /* * ubo.viewMat */ * ubo.modelMat * vec4(x, y, 0, 1.0);

    colOut = ubo.col;

    uvOut = vec2(u, v);
    drawSize = ubo.drawSize;
}
