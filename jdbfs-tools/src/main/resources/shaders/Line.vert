#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

struct Point {
	vec2 xy;
	float rad;
	int8_t flag;
	u8vec4 color;
};


layout (binding = 0) readonly uniform GlobalUniforms {
	mat4 projectionMat;
} gUbo;
layout (binding = 1) readonly uniform Uniforms {
	mat4 modelMat;
} ubo;

layout (binding = 2) readonly buffer Points { Vert data[]; } in_points;

layout (location = 0) out vec4 colOut;

void main() {

	Point pt = in_points.data[gl_VertexIndex];

	gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * ubo.modelMat * vec4(pt.xy, 0, 1.0);

	colOut = vec4(vt.color)/255.0;
}
