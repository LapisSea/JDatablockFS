#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

#include <vec4_u8>

struct Vert {
	vec2 xy;
	vec4_u8 color;
};


layout (set = 0, binding = 0) readonly uniform GlobalUniforms {
	mat4 projectionMat;
} gUbo;
layout (set = 1, binding = 0) readonly uniform Uniforms {
	mat4 modelMat;
} ubo;

layout (set = 1, binding = 1) readonly buffer Verts { Vert data[]; } in_verts;

layout (location = 0) out vec4 colOut;

void main() {

	Vert vt = in_verts.data[gl_VertexIndex];

	gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * ubo.modelMat * vec4(vt.xy, 0, 1.0);

	colOut = toVec4(vt.color);
}
