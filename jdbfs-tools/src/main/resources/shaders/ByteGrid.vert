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
    float scale;
} ubo;
//layout (set = 1, binding = 2) readonly buffer Bytes { Byte data[]; } in_bytes;

layout (location = 0) out vec3 colOut;

void main() {

    Vert vt = in_verts.data[gl_VertexIndex];


    //    int tileX = vt.tileIndex % tileWidth;
    //    int tileY = vt.tileIndex / tileWidth;


    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * ubo.modelMat * vec4(vt.x, vt.y, 0, 1.0);


    if (vt.type == 0) {
        colOut = vec3(1, 0, 0);
    } else if (vt.type == 1) {
        colOut = vec3(0, 1, 0);
    } else {
        colOut = vec3(0, 0, 1);
    }

}
