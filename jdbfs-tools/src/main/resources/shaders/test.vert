#version 460

vec2 pos[3] = vec2[3](vec2(-0.7, 0.7), vec2(0.7, 0.7), vec2(0.0, -0.7));

vec3 col[3] = vec3[3](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));

layout (location = 0) out vec3 colOut;

void main() {
    gl_Position = vec4(pos[gl_VertexIndex], 0.0, 1.0);
    colOut = col[gl_VertexIndex];
}
