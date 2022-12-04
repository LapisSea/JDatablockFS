package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.ClassName;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static org.objectweb.asm.Opcodes.*;

public record GenericType(ClassName raw, int dims, List<GenericType> args){
	
	public static final GenericType OBJECT = new GenericType(ClassName.of(Object.class));
	public static final GenericType STRING = new GenericType(ClassName.of(String.class));
	public static final GenericType INT    = new GenericType(ClassName.of(int.class));
	public static final GenericType BOOL   = new GenericType(ClassName.of(boolean.class));
	public static final GenericType FLOAT  = new GenericType(ClassName.of(float.class));
	
	public static GenericType of(Type type){
		
		if(type instanceof Class<?> c){
			int dims = 0;
			while(c.isArray()){
				c = c.componentType();
				dims++;
			}
			return new GenericType(ClassName.of(c), dims, List.of());
		}
		
		if(type instanceof ParameterizedType p){
			var raw   = of(p.getRawType());
			var tArgs = p.getActualTypeArguments();
			var args  = new ArrayList<GenericType>(tArgs.length);
			for(var arg : tArgs){
				args.add(of(arg));
			}
			return new GenericType(raw.raw, raw.dims, args);
		}
		
		throw new NotImplementedException(type.getClass().getName());
	}
	
	public GenericType(ClassName raw){
		this(raw, 0, List.of());
	}
	
	public GenericType(ClassName raw, int dims, List<GenericType> args){
		this.raw = Objects.requireNonNull(raw);
		this.dims = dims;
		this.args = List.copyOf(args);
	}
	
	public record BaseType(String jvmStr, Class<?> type, int returnOp, int loadOp, int slots){
		public static final BaseType OBJ  = new BaseType("O", Object.class, ARETURN, ALOAD, 1);
		public static final BaseType VOID = new BaseType("V", void.class, RETURN, -1, 0);
	}
	
	private static final Map<String, BaseType> PRIMITIVES = Map.of(
		"void", BaseType.VOID,
		"char", new BaseType("C", char.class, IRETURN, ILOAD, 1),
		"byte", new BaseType("B", byte.class, IRETURN, ILOAD, 1),
		"short", new BaseType("S", short.class, IRETURN, ILOAD, 1),
		"int", new BaseType("I", int.class, IRETURN, ILOAD, 1),
		"long", new BaseType("J", long.class, LRETURN, LLOAD, 2),
		"float", new BaseType("F", float.class, FRETURN, FLOAD, 1),
		"double", new BaseType("D", double.class, DRETURN, DLOAD, 2),
		"boolean", new BaseType("Z", boolean.class, IRETURN, ILOAD, 1)
	);
	
	public BaseType getBaseType(){
		if(dims == 0) return BaseType.OBJ;
		return PRIMITIVES.getOrDefault(raw.any(), BaseType.OBJ);
	}
	
	public Optional<BaseType> getPrimitiveType(){
		if(dims == 0) return Optional.empty();
		return Optional.ofNullable(PRIMITIVES.get(raw.any()));
	}
	
	public CharSequence jvmSignature(){
		return jvmString(true);
	}
	public CharSequence jvmDescriptor(){
		return jvmString(false);
	}
	public void jvmSignature(StringBuilder sb){
		sb.ensureCapacity(sb.length() + jvmSignatureLen());
		jvmString(sb, true);
	}
	public void jvmDescriptor(StringBuilder sb){
		sb.ensureCapacity(sb.length() + jvmDescriptorLen());
		jvmString(sb, false);
	}
	public int jvmSignatureLen(){
		return jvmStringLen(true);
	}
	public int jvmDescriptorLen(){
		return jvmStringLen(false);
	}
	
	public CharSequence jvmString(boolean includeGenerics){
		if(dims == 0){
			var primitive = getPrimitiveType().map(BaseType::jvmStr);
			if(primitive.isPresent()) return primitive.get();
		}
		
		StringBuilder sb = new StringBuilder(jvmStringLen(includeGenerics));
		jvmString(sb, includeGenerics);
		return sb;
	}
	
	public int jvmStringLen(boolean includeGenerics){
		var primitive = getPrimitiveType().map(BaseType::jvmStr).orElse(null);
		
		int len = dims;
		
		if(primitive == null){
			len += 1 + raw.any().length();
			if(includeGenerics && !args.isEmpty()){
				for(var arg : args){
					len += arg.jvmStringLen(true);
				}
				len += 2;
			}
			len++;
		}else{
			len += primitive.length();
		}
		return len;
	}
	
	public void jvmString(StringBuilder sb, boolean includeGenerics){
		var primitive = getPrimitiveType().map(BaseType::jvmStr).orElse(null);
		
		
		for(int i = 0; i<dims; i++){
			sb.append('[');
		}
		
		if(primitive == null){
			sb.append('L').append(raw.slashed());
			if(includeGenerics && !args.isEmpty()){
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
	
	public boolean instanceOf(TypeSource source, GenericType right) throws MalformedJorthException{
		var thisInfo = this.getPrimitiveType().orElse(null);
		var thatInfo = right.getPrimitiveType().orElse(null);
		//If different types of base types
		if(thisInfo != thatInfo) return false;
		//if is primitive and equal, nothing else to check
		if(thisInfo != null) return true;
		
		//Instance of object and not primitive, always true
		if(right.equals(GenericType.OBJECT)) return true;
		
		if(dims != right.dims) return false;
		
		if(!raw.instanceOf(source, right.raw)){
			return false;
		}
		
		if(args.size() != right.args.size()){
			//1 raw but other not
			return this.args.isEmpty() || right.args.isEmpty();
		}
		
		for(int i = 0; i<args.size(); i++){
			var leftArg  = args.get(i);
			var rightArg = right.args.get(i);
			if(!leftArg.instanceOf(source, rightArg)){
				return false;
			}
		}
		return true;
	}
	
	@Override
	public String toString(){
		return raw.dotted() + "[]".repeat(dims) + (
			args.isEmpty()? "" :
			args.stream()
			    .map(GenericType::toString)
			    .collect(joining(", ", "<", ">"))
		);
	}
	
	public GenericType withoutArgs(){
		if(args.isEmpty()) return this;
		return new GenericType(raw, dims, List.of());
	}
}
