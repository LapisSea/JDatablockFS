#version 460

layout (location = 0) in vec2 uv;
layout (location = 1) in vec4 color;

layout (location = 0) out vec4 out_color;

layout (binding = 1) uniform sampler2D texSampler;

void main() {
	out_color = color;
	out_color.a*=texture(texSampler, uv).r;
}
