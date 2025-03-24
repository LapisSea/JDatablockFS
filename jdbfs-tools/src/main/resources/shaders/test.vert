#version 460

struct Vert {
    float x, y;
    float r, g, b;
    float u, v;
};


layout (binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;
layout (binding = 1) readonly uniform Uniforms {
    mat4 modelMat;
} ubo;

layout (binding = 2) readonly buffer Verts { Vert data[]; } in_verts;

layout (location = 0) out vec3 colOut;
layout (location = 1) out vec2 uvOut;

void main() {

    Vert vt = in_verts.data[gl_VertexIndex];

    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * ubo.modelMat * vec4(vt.x, vt.y, 0, 1.0);

    colOut = vec3(vt.r, vt.g, vt.b);
    uvOut = vec2(vt.u, vt.v);
}
