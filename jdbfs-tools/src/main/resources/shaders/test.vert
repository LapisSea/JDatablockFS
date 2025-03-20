#version 460

vec2 pos[3] = vec2[3](vec2(-0.7, 0.7), vec2(0.7, 0.7), vec2(0.0, -0.7));

vec3 col[3] = vec3[3](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));

struct Vert {
    float x, y;
    float r, g, b;
};

layout (binding = 0) readonly buffer Verts { Vert data[]; } in_verts;

layout (location = 0) out vec3 colOut;

void main() {

    Vert vt = in_verts.data[gl_VertexIndex];

    gl_Position = vec4(vt.x, vt.y, 0.5, 1.0);

    colOut = vec3(vt.r, vt.g, vt.b);
}
