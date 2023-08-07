package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.joining;

public record GenericType(ClassName raw, int dims, List<JType> args) implements JType{
	
	public static final GenericType OBJECT = new GenericType(ClassName.of(Object.class));
	public static final GenericType STRING = new GenericType(ClassName.of(String.class));
	public static final GenericType INT    = new GenericType(ClassName.of(int.class));
	public static final GenericType BOOL   = new GenericType(ClassName.of(boolean.class));
	public static final GenericType FLOAT  = new GenericType(ClassName.of(float.class));
	
	public static GenericType of(Type type){
		return of0(null, type);
	}
	static GenericType of0(Set<Type> stack, Type type){
		
		if(type instanceof Class<?> c){
			int dims = 0;
			while(c.isArray()){
				c = c.componentType();
				dims++;
			}
			return new GenericType(ClassName.of(c), dims, List.of());
		}
		
		if(type instanceof ParameterizedType p){
			var raw   = of0(stack, p.getRawType());
			var tArgs = p.getActualTypeArguments();
			var args  = new ArrayList<JType>(tArgs.length);
			for(var arg : tArgs){
				args.add(JType.of(stack, arg));
			}
			return new GenericType(raw.raw, raw.dims, args);
		}
		
		if(type instanceof GenericArrayType p){
			var t = of0(stack, p.getGenericComponentType());
			return t.withDims(t.dims + 1);
		}
		if(type instanceof TypeVariable<?> var){
			if(stack == null) stack = new HashSet<>();
			if(stack.contains(var)){
				var bounds = var.getBounds();
				return of(switch(bounds[0]){
					case Class<?> c -> c;
					case ParameterizedType t -> t.getRawType();
					default -> throw new NotImplementedException(bounds[0].getClass().getName());
				});
			}
			stack.add(var);
			var bounds = var.getBounds()[0];
			return of0(stack, bounds);
		}
		if(type instanceof WildcardType w){
			var up = w.getUpperBounds();
			if(up.length != 1) throw new NotImplementedException();
			return of0(stack, up[0]);
		}
		throw new NotImplementedException(type.getClass().getName());
	}
	
	public GenericType(ClassName raw){
		this(raw, 0, List.of());
	}
	
	public GenericType(ClassName raw, int dims, List<JType> args){
		this.raw = Objects.requireNonNull(raw);
		this.dims = dims;
		this.args = List.copyOf(args);
	}
	
	@Override
	public BaseType getBaseType(){
		if(dims != 0) return BaseType.OBJ;
		return BaseType.of(raw);
	}
	@Override
	public boolean hasArgs(){
		return !args.isEmpty();
	}
	
	@Override
	public Optional<BaseType> getPrimitiveType(){
		if(dims != 0) return Optional.empty();
		return Optional.ofNullable(BaseType.ofPrimitive(raw));
	}
	
	@Override
	public int jvmStringLen(boolean includeGenerics){
		var primitive = getPrimitiveType().map(BaseType::jvmStr).orElse(null);
		
		int len = dims;
		
		if(primitive == null){
			var t = BaseType.ofPrimitive(raw);
			if(t != null) len++;
			else len += 1 + raw.any().length();
			if(includeGenerics && !args.isEmpty()){
				for(var arg : args){
					len += arg.jvmStringLen(true);
				}
				len += 2;
			}
			if(t == null) len++;
		}else{
			len += primitive.length();
		}
		return len;
	}
	
	@Override
	public void jvmString(StringBuilder sb, boolean includeGenerics){
		var primitive = getPrimitiveType().map(BaseType::jvmStr).orElse(null);
		
		
		for(int i = 0; i<dims; i++){
			sb.append('[');
		}
		
		if(primitive == null){
			var t = BaseType.ofPrimitive(raw);
			if(t != null) sb.append(t.jvmStr);
			else sb.append('L').append(raw.slashed());
			
			if(includeGenerics && !args.isEmpty()){
				sb.append('<');
				for(var arg : args){
					arg.jvmString(sb, true);
				}
				sb.append('>');
			}
			if(t == null) sb.append(';');
		}else{
			sb.append(primitive);
		}
	}
	
	public boolean instanceOf(TypeSource source, GenericType right) throws MalformedJorth{
		var thisInfo = this.getPrimitiveType().orElse(null);
		var thatInfo = right.getPrimitiveType().orElse(null);
		//If different types of base types
		if(thisInfo != thatInfo) return false;
		//if is primitive and equal, nothing else to check
		if(thisInfo != null) return true;
		
		//Instance of an object and not primitive, always true
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
			var leftArg  = args.get(i).asGeneric();
			var rightArg = right.args.get(i).asGeneric();
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
			    .map(Object::toString)
			    .collect(joining(", ", "<", ">"))
		);
	}
	
	@Override
	public GenericType withoutArgs(){
		if(args.isEmpty()) return this;
		return new GenericType(raw, dims, List.of());
	}
	
	public GenericType withDims(int dims){
		return new GenericType(raw, dims, args);
	}
}
