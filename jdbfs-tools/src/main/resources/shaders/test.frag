#version 460

layout (location = 0) out vec4 out_color;

layout (location = 0) in vec3 col;
layout (location = 1) in vec2 uv;


layout (binding = 2) uniform sampler2D texSampler;

void main() {
    out_color = vec4(col, 1.0);
}
