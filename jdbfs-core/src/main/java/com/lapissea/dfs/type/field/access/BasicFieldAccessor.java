package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.function.Function.identity;

public abstract class BasicFieldAccessor<CTyp extends IOInstance<CTyp>> implements FieldAccessor<CTyp>, Stringify{
	
	public abstract static class ReadOnly<CTyp extends IOInstance<CTyp>> extends BasicFieldAccessor<CTyp>{
		
		private final boolean readOnlyField;
		
		protected ReadOnly(Struct<CTyp> declaringStruct, String name, Collection<Annotation> annotations, boolean readOnlyField){
			super(declaringStruct, name, annotations);
			this.readOnlyField = readOnlyField;
		}
		protected ReadOnly(Struct<CTyp> declaringStruct, String name, Map<Class<? extends Annotation>, ? extends Annotation> annotations, boolean readOnlyField){
			super(declaringStruct, name, annotations);
			this.readOnlyField = readOnlyField;
		}
		
		protected void checkReadOnlyField(){
			if(readOnlyField){
				failReadOnly();
			}
		}
		private void failReadOnly(){
			throw new UnsupportedOperationException("Field for " + getName() + " is final, can not set it!");
		}
	}
	
	protected static void validateSetter(Type fieldType, Method func){
		if(!Utils.genericInstanceOf(func.getReturnType(), Void.TYPE)){
			throw new MalformedStruct("setter returns\n" + func.getReturnType() + " but\n" + fieldType + " is required\nSetter: " + func);
		}
		if(func.getParameterCount() != 1){
			throw new MalformedStruct("setter must have 1 argument of " + fieldType + "\n" + func);
		}
		var funType = func.getGenericParameterTypes()[0];
		if(
			!Utils.genericInstanceOf(funType, fieldType) &&
			!Utils.genericInstanceOf(FieldCompiler.getType(funType, func::getAnnotation), fieldType)
		){
			throw new MalformedStruct("setter argument is " + func.getGenericParameterTypes()[0] + " but " + fieldType + " is required\n" + func);
		}
	}
	
	protected static void validateGetter(Type fieldType, Method func){
		var funType = func.getGenericReturnType();
		if(
			!Utils.genericInstanceOf(funType, fieldType) &&
			!Utils.genericInstanceOf(FieldCompiler.getType(funType, func::getAnnotation), fieldType)
		){
			throw new MalformedStruct("getter returns\n" + func.getGenericReturnType() + " but\n" + fieldType + " is required\nGetter: " + func);
		}
		if(func.getParameterCount() != 0){
			throw new MalformedStruct("getter must not have arguments\n" + func);
		}
	}
	
	private final Struct<CTyp> declaringStruct;
	private final String       name;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	protected BasicFieldAccessor(Struct<CTyp> declaringStruct, String name, Collection<Annotation> annotations){
		this(declaringStruct, name, Iters.from(annotations).toMap(Annotation::annotationType, identity()));
	}
	protected BasicFieldAccessor(Struct<CTyp> declaringStruct, String name,
	                             Map<Class<? extends Annotation>, ? extends Annotation> annotations){
		this.declaringStruct = declaringStruct;
		this.name = Objects.requireNonNull(name);
		this.annotations = Map.copyOf(annotations);
	}
	
	@NotNull
	@Nullable
	@Override
	@SuppressWarnings("unchecked")
	public final <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return (Optional<T>)Optional.ofNullable(annotations.get(annotationClass));
	}
	@Override
	public final boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return annotations.containsKey(annotationClass);
	}
	
	@Override
	public final Struct<CTyp> getDeclaringStruct(){
		return declaringStruct;
	}
	
	@NotNull
	@Override
	public String getName(){
		return name;
	}
	
	protected String strName(){ return getName(); }
	
	@Override
	public String toString(){
		var struct = getDeclaringStruct();
		return Utils.typeToHuman(getGenericType(null)) + " " + (struct == null? "" : struct.cleanName()) + "#" + strName();
	}
	@Override
	public String toShortString(){
		return Utils.typeToHuman(getGenericType(null)) + " " + strName();
	}
	
	@Override
	public boolean equals(Object o){
		return this == o ||
		       o instanceof FieldAccessor<?> that &&
		       Objects.equals(getDeclaringStruct(), that.getDeclaringStruct()) &&
		       getName().equals(that.getName());
	}
	
	@Override
	public int hashCode(){
		var struct = getDeclaringStruct();
		int result = struct == null? 0 : struct.hashCode();
		result = 31*result + getName().hashCode();
		return result;
	}
}
