#version 460

struct Vert {
    float x, y;
    float r, g, b;
};

layout (binding = 0) readonly buffer Verts { Vert data[]; } in_verts;

layout (binding = 1) readonly uniform Uniforms {
    mat4 modelMat;
    mat4 projectionMat;
} ubo;

layout (location = 0) out vec3 colOut;

void main() {

    Vert vt = in_verts.data[gl_VertexIndex];

    gl_Position = ubo.projectionMat /* * ubo.viewMat */ * ubo.modelMat * vec4(vt.x, vt.y, 0, 1.0);

    colOut = vec3(vt.r, vt.g, vt.b);
}
