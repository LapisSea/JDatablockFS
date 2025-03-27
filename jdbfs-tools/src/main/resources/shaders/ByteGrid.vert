#version 460

struct Vert {
    float x;
    float y;
    int type;
};


layout (binding = 0) readonly uniform GlobalUniforms {
    mat4 projectionMat;
} gUbo;
layout (binding = 1) readonly uniform Uniforms {
    mat4 modelMat;
    int tileWidth;
} ubo;

layout (binding = 2) readonly buffer Verts { Vert data[]; } in_verts;

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
