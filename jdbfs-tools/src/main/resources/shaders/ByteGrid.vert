#version 460
#extension GL_EXT_shader_explicit_arithmetic_types: require

#define T_BACK 0
#define T_SET  1
#define T_MARK 2

struct Vert {
    float x;
    float y;
    int type;
};

struct Byte {
    uint16_t index;
    uint8_t flag;
    uint8_t value;
    u8vec4 color;
};

layout (set = 0, binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;

layout (set = 1, binding = 0) readonly buffer Verts { Vert data[]; } in_verts;

layout (set = 2, binding = 0) readonly uniform Uniforms {
    mat4 modelMat;
    int tileWidth;
    vec4 flagColors[4];
} ubo;
layout (set = 2, binding = 1) readonly buffer Bytes { Byte data[]; } in_bytes;

layout (location = 0) out vec4 colOut;

layout (constant_id = 0) const bool simple = false;

const Vert simpleVerts[] = {
    {0.0, 0.0, 0},
    {0.0, 1.0, 0},
    {1.0, 1.0, 0},

    {0.0, 0.0, 0},
    {1.0, 1.0, 0},
    {1.0, 0.0, 0},

    {0.0, 0.0, 1},
    {0.0, 1.0, 1},
    {1.0, 1.0, 1},

    {0.0, 0.0, 1},
    {1.0, 1.0, 1},
    {1.0, 0.0, 1},
};

void main() {

    Byte byt = in_bytes.data[gl_InstanceIndex];

    Vert vt;
    if(simple){
        int vertIdx=gl_VertexIndex-gl_BaseVertex;
        if(vertIdx>=12){
            vt=Vert(0.,0.,0);
        }else{
            vt = simpleVerts[vertIdx];
            if(vt.type == T_SET && vt.y > 0){
                vt.y = byt.value/255.0;
            }
        }
    }else{
        vt = in_verts.data[gl_VertexIndex];
    }



    uint tileX = byt.index % ubo.tileWidth;
    uint tileY = byt.index / ubo.tileWidth;

    gl_Position = gUbo.projectionMat /* * gUbo.viewMat */ * ubo.modelMat * vec4(vt.x + tileX, vt.y + tileY, 0, 1.0);

    vec4 col = vec4(byt.color) / 255.0;
    if (vt.type == T_BACK) {
        colOut = col / 2;
    } else if (vt.type == T_SET) {
        colOut = col;
    } else if (vt.type == T_MARK){
        if(simple) colOut = ubo.flagColors[0];
        else colOut = ubo.flagColors[byt.flag];
    }else {
        colOut = vec4(0, 0, 1, 1);
    }

}
