#ifdef NO_ARITHMETIC_TYPES

#define vec4_u8 uint
vec4 toVec4(vec4_u8 value){
	return unpackUnorm4x8(value);
}

#else

#define vec4_u8 u8vec4
vec4 toVec4(vec4_u8 value){
	return vec4(value) / 255.0;
}

#endif
