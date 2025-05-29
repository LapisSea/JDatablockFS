#version 460

layout (location = 0) in vec2 pos;
layout (location = 1) in vec2 uv;
layout (location = 2) in vec4 color;

layout (location = 0) out vec2 uvOut;
layout (location = 1) out vec4 colOut;

layout (push_constant) uniform Push {
	vec2 offset;
	vec2 scale;
} push;

void main() {
	uvOut=uv;
	colOut=color;
	gl_Position = vec4((pos.xy+push.offset)*push.scale*2.-1., 0, 1.0);
}
