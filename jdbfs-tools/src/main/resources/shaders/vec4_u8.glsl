#ifdef NO_ARITHMETIC_TYPES
struct vec4_u8{
	uint val;
};
vec4 toVec4(vec4_u8 value){
	return unpackUnorm4x8(value.val);
}
#else
struct vec4_u8{
	u8vec4 val;
};
vec4 toVec4(vec4_u8 value){
	return vec4(value.val) / 255.0;
}
#endif