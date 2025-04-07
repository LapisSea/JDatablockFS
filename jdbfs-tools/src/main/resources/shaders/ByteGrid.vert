#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

struct Vert {
    float x;
    float y;
    int type;
};

struct Byte {
    int index;
    u8vec4 color;
};

layout (set = 0, binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;

layout (set = 1, binding = 0) readonly buffer Verts { Vert data[]; } in_verts;

layout (set = 2, binding = 0) readonly uniform Uniforms {
    mat4 modelMat;
    int tileWidth;
} ubo;
layout (set = 2, binding = 1) readonly buffer Bytes { Byte data[]; } in_bytes;

layout (location = 0) out vec4 colOut;

void main() {

    Vert vt = in_verts.data[gl_VertexIndex];
    Byte byt = in_bytes.data[gl_InstanceIndex];

    int tileX = byt.index % ubo.tileWidth;
    int tileY = byt.index / ubo.tileWidth;

    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * ubo.modelMat * vec4(vt.x + tileX, vt.y + tileY, 0, 1.0);

    vec4 col = vec4(byt.color) / 255.0;
    if (vt.type == 0) {
        colOut = col / 2;
    } else if (vt.type == 1) {
        colOut = col;
    } else {
        colOut = vec4(0, 0, 1, 1);
    }

}
