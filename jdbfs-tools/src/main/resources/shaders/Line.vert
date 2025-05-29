#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

#include <vec4_u8>

layout (push_constant) uniform Push {
	mat3x2 modelViewProjection;
} push;

layout (location = 0) in vec2 xy;
layout (location = 1) in vec4_u8 color;

layout (location = 0) out vec4 colOut;

void main() {
	gl_Position = vec4(push.modelViewProjection * vec3(xy, 1.0), 0, 1.0);
	colOut = toVec4(color);
}
