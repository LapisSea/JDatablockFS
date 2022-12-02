package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.v2.lang.ClassName;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GenericType(ClassName raw, int dims, List<GenericType> args){
	
	public static final GenericType OBJECT=new GenericType(ClassName.of(Object.class), 0, List.of());
	
	public GenericType(ClassName raw, int dims, List<GenericType> args){
		this.raw=Objects.requireNonNull(raw);
		this.dims=dims;
		this.args=List.copyOf(args);
	}
	
	private static final Map<String, String> PRIMITIVES=Map.of(
		"boolean", "Z",
		"void", "V",
		"char", "C",
		"byte", "B",
		"short", "S",
		"int", "I",
		"long", "J",
		"float", "F",
		"double", "D"
	);
	
	public CharSequence jvmSignature(){
		return jvmString(true);
	}
	public CharSequence jvmDescriptor(){
		return jvmString(false);
	}
	public void jvmSignature(StringBuilder sb){
		sb.ensureCapacity(sb.length()+jvmStringLen(true));
		jvmString(sb, true);
	}
	public void jvmDescriptor(StringBuilder sb){
		sb.ensureCapacity(sb.length()+jvmStringLen(false));
		jvmString(sb, false);
	}
	
	private CharSequence jvmString(boolean includeGenerics){
		var primitive=PRIMITIVES.get(raw.any());
		if(primitive!=null&&dims==0) return primitive;
		
		StringBuilder sb=new StringBuilder(jvmStringLen(includeGenerics));
		jvmString(sb, includeGenerics);
		return sb;
	}
	
	private int jvmStringLen(boolean includeGenerics){
		var primitive=PRIMITIVES.get(raw.any());
		
		int len=dims;
		
		if(primitive==null){
			len+=1+raw.any().length();
			if(includeGenerics&&!args.isEmpty()){
				for(var arg : args){
					len+=arg.jvmStringLen(true);
				}
				len+=2;
			}
			len++;
		}else{
			len+=primitive.length();
		}
		return len;
	}
	
	private void jvmString(StringBuilder sb, boolean includeGenerics){
		var primitive=PRIMITIVES.get(raw.any());
		
		for(int i=0;i<dims;i++){
			sb.append('[');
		}
		
		if(primitive==null){
			sb.append('L').append(raw.slashed());
			if(includeGenerics&&!args.isEmpty()){
				sb.append('<');
				for(var arg : args){
					arg.jvmString(sb, true);
				}
				sb.append('>');
			}
			sb.append(';');
		}else{
			sb.append(primitive);
		}
	}
}
