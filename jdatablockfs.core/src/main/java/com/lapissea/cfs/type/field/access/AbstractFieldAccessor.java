package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.util.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;

public abstract class AbstractFieldAccessor<CTyp extends IOInstance<CTyp>> implements FieldAccessor<CTyp>, Stringify{
	
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
	
	protected AbstractFieldAccessor(Struct<CTyp> declaringStruct, String name){
		this.declaringStruct = declaringStruct;
		this.name = Objects.requireNonNull(name);
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
		return getType().getName() + " " + (struct == null? "" : struct.cleanName()) + "#" + strName();
	}
	@Override
	public String toShortString(){
		return getType().getSimpleName() + " " + strName();
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
