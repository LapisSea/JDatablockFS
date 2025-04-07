#version 460

layout (location = 0) out vec4 out_color;
layout (location = 0) in vec4 col;
void main() { out_color = col; }
