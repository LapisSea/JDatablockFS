#version 460

layout (location = 0) in vec2 uv;
layout (location = 1) in vec4 color;

layout (location = 0) out vec4 out_color;

layout (binding = 0) uniform sampler2D texSampler;

layout(push_constant) uniform PushFrag {
	layout(offset = 16) bool isMask;
} push;

void main() {
	vec4 px=texture(texSampler, uv);
	out_color = color;
	if(push.isMask){
		out_color.a*=px.r;
	}else{
		out_color*=px;
	}
}
