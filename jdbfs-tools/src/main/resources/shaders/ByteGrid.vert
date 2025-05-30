#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

#define T_BACK 0
#define T_SET  1
#define T_MARK 2

#include <vec4_u8>

struct Vert {
	float x;
	float y;
	int type;
};

#ifdef NO_ARITHMETIC_TYPES
	struct Byte {
		uint infoBytes;
		vec4_u8 color;
	};
	uint byteIndex(Byte value){return value.infoBytes & 0xFFFF;}
	uint byteFlag(Byte value){return (value.infoBytes >> 16) & 0xFF;}
	uint byteValue(Byte value){return (value.infoBytes >> 24) & 0xFF;}
#else
	struct Byte {
		uint16_t index;
		uint8_t flag;
		uint8_t value;
		vec4_u8 color;
	};
	uint byteIndex(Byte value){return value.index;}
	uint byteFlag(Byte value){return value.flag;}
	uint byteValue(Byte value){return value.value;}
#endif


layout (set = 0, binding = 0) readonly buffer Verts { Vert data[]; } in_verts;

layout (push_constant) uniform Uniforms {
	mat4 mvpMat;
	uvec4 flagColors;
	int tileWidth;
} ubo;
layout (set = 1, binding = 0) readonly buffer Bytes { Byte data[]; } in_bytes;

layout (location = 0) out vec4 colOut;

layout (constant_id = 0) const bool simple = false;

const Vert simpleVerts[] = {
	{ 0.0, 0.0, 0 },
	{ 0.0, 1.0, 0 },
	{ 1.0, 1.0, 0 },

	{ 0.0, 0.0, 0 },
	{ 1.0, 1.0, 0 },
	{ 1.0, 0.0, 0 },

	{ 0.0, 0.0, 1 },
	{ 0.0, 1.0, 1 },
	{ 1.0, 1.0, 1 },

	{ 0.0, 0.0, 1 },
	{ 1.0, 1.0, 1 },
	{ 1.0, 0.0, 1 },
};

void main() {

	Byte byt = in_bytes.data[gl_InstanceIndex];

	Vert vt;
	if (simple){
		int vertIdx=gl_VertexIndex-gl_BaseVertex;
		if (vertIdx>=12){
			vt=Vert(0., 0., 0);
		} else {
			vt = simpleVerts[vertIdx];
			if (vt.type == T_SET && vt.y > 0){
				vt.y = byteValue(byt)/255.0;
			}
		}
	} else {
		vt = in_verts.data[gl_VertexIndex];
	}

	
	uint index = byteIndex(byt);
	uint tileX = index % ubo.tileWidth;
	uint tileY = index / ubo.tileWidth;

	gl_Position =ubo.mvpMat * vec4( vt.x + tileX, vt.y + tileY, 0, 1.0);

	vec4 col = toVec4(byt.color);
	if (vt.type == T_BACK) {
		colOut = col / 2;
	} else if (vt.type == T_SET) {
		colOut = col;
	} else if (vt.type == T_MARK){
		uint col8;
		if (simple) col8 = ubo.flagColors[0];
		else col8 = ubo.flagColors[byteFlag(byt)];
		colOut = unpackUnorm4x8(col8);
	} else {
		colOut = vec4(0, 0, 1, 1);
	}

}
