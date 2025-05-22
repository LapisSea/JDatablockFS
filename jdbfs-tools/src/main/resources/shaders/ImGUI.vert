#version 460

layout (location = 0) in vec2 pos;
layout (location = 1) in vec2 uv;
layout (location = 2) in vec4 color;

layout (location = 0) out vec2 uvOut;
layout (location = 1) out vec4 colOut;

layout (binding = 0) readonly uniform GlobalUniforms {
	mat4 projectionMat;
} gUbo;

void main() {
	uvOut=uv;
	colOut=color;
	gl_Position = gUbo.projectionMat * vec4(pos.xy, 0, 1.0);
}
