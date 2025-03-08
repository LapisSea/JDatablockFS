#version 460

layout (location = 0) out vec4 out_color;

layout (location = 0) in vec3 colOut;

void main() {
    out_color = vec4(colOut, 1.0);
}
