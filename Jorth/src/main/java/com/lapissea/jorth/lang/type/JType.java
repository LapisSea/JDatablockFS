package com.lapissea.jorth.lang.type;

import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public sealed interface JType permits GenericType, JType.Wildcard{
	record Wildcard(List<JType> lower, List<JType> upper) implements JType{
		@Override
		public int jvmStringLen(boolean includeGenerics){
			if(!includeGenerics){
				return asGeneric().jvmStringLen(false);
			}
			
			if(lower.isEmpty()){
				if(noUpper()) return 1;
				if(upper.size() != 1) throw new UnsupportedOperationException();
				return 1 + upper.get(0).jvmStringLen(true);
			}else{
				if(lower.size() != 1) throw new UnsupportedOperationException();
				return 1 + lower.get(0).jvmStringLen(true);
			}
		}
		
		@Override
		public void jvmString(StringBuilder sb, boolean includeGenerics){
			if(!includeGenerics){
				asGeneric().jvmString(sb, false);
				return;
			}
			
			JType b;
			if(lower.isEmpty()){
				if(noUpper()){
					sb.append('*');
					return;
				}
				if(upper.size() != 1) throw new UnsupportedOperationException();
				b = upper.get(0);
				sb.append('+');
			}else{
				if(lower.size() != 1) throw new UnsupportedOperationException();
				b = lower.get(0);
				sb.append('-');
			}
			
			b.jvmString(sb, true);
		}
		
		@Override
		public Optional<BaseType> getPrimitiveType(){
			return Optional.empty();
		}
		@Override
		public BaseType getBaseType(){
			return BaseType.OBJ;
		}
		@Override
		public boolean hasArgs(){
			return true;
		}
		@Override
		public JType withoutArgs(){
			return asGeneric().withoutArgs();
		}
		
		@Override
		public String toString(){
			String      ext;
			List<JType> bounds;
			if(lower.size() == 0){
				if(noUpper()){
					return "?";
				}
				bounds = upper;
				ext = "extends";
			}else{
				bounds = lower;
				ext = "super";
			}
			
			return bounds.stream().map(Object::toString)
			             .collect(Collectors.joining(" & ", "? " + ext + " ", ""));
		}
		
		private boolean noUpper(){
			return upper.size() == 0 || upper.get(0).equals(GenericType.OBJECT);
		}
	}
	
	static JType of(Type type){
		return of(null, type);
	}
	static JType of(Set<Type> stack, Type type){
		return switch(type){
			case WildcardType wild -> new Wildcard(Arrays.stream(wild.getLowerBounds()).map(JType::of).toList(),
			                                       Arrays.stream(wild.getUpperBounds()).map(JType::of).toList());
			default -> GenericType.of0(stack, type);
		};
	}
	
	default String jvmSignatureStr(){
		return jvmSignature().toString();
	}
	
	default CharSequence jvmSignature() { return jvmString(true); }
	default CharSequence jvmDescriptor(){ return jvmString(false); }
	default void jvmSignature(StringBuilder sb){
		sb.ensureCapacity(sb.length() + jvmSignatureLen());
		jvmString(sb, true);
	}
	default void jvmDescriptor(StringBuilder sb){
		sb.ensureCapacity(sb.length() + jvmDescriptorLen());
		jvmString(sb, false);
	}
	default int jvmSignatureLen() { return jvmStringLen(true); }
	default int jvmDescriptorLen(){ return jvmStringLen(false); }
	
	default CharSequence jvmString(boolean includeGenerics){
		var primitive = getPrimitiveType().map(BaseType::jvmStr);
		if(primitive.isPresent()) return primitive.get();
		
		var sb = new StringBuilder(jvmStringLen(includeGenerics));
		jvmString(sb, includeGenerics);
		return sb;
	}
	
	int jvmStringLen(boolean includeGenerics);
	void jvmString(StringBuilder sb, boolean includeGenerics);
	Optional<BaseType> getPrimitiveType();
	BaseType getBaseType();
	
	default GenericType asGeneric(){
		return switch(this){
			case GenericType g -> g;
			case Wildcard wild -> {
				var lower = wild.lower;
				if(!lower.isEmpty()){
					if(lower.size() != 1) throw new UnsupportedOperationException();
					yield lower.get(0).asGeneric();
				}
				var upper = wild.upper;
				if(upper.size() != 1) throw new UnsupportedOperationException();
				yield upper.get(0).asGeneric();
			}
		};
	}
	boolean hasArgs();
	JType withoutArgs();
}
