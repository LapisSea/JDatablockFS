package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Match;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.util.Objects;

public final class QuerySupport{
	
	private static final WeakKeyValueMap<Struct.FieldRef<?, ?>, IOField<?, ?>> IOF_CACHE = new WeakKeyValueMap.Sync<>();
	
	static <T extends IOInstance<T>, V> IOField<T, V> asIOField(Struct.FieldRef<T, V> ref){
		var cached = IOF_CACHE.get(ref);
		if(cached != null){
			//noinspection unchecked
			return (IOField<T, V>)cached;
		}
		
		var val = asIOFieldPure(ref);
		IOF_CACHE.put(ref, val);
		return val;
	}
	
	static <T extends IOInstance<T>, V> IOField<T, V> asIOFieldPure(Struct.FieldRef<T, V> ref){
		if(!ref.getClass().isHidden()){
			throw new IllegalArgumentException(ref.getClass() + " must be a JVM produced lambda");
		}
		
		var lambda = asSerializedLambda(ref);
		
		var implClassName = lambda.getImplClass();
		if(implClassName == null) throw new IllegalStateException("implClassName is null");
		implClassName = implClassName.replace('/', '.');
		var memberName = lambda.getImplMethodName();
		
		var errName = switch(lambda.getImplMethodKind()){
			case MethodHandleInfo.REF_getField -> "getField";
			case MethodHandleInfo.REF_getStatic -> "getStatic";
			case MethodHandleInfo.REF_putField -> "putField";
			case MethodHandleInfo.REF_putStatic -> "putStatic";
			case MethodHandleInfo.REF_invokeVirtual -> null; //For classes
			case MethodHandleInfo.REF_invokeStatic -> "invokeStatic";
			case MethodHandleInfo.REF_invokeSpecial -> "invokeSpecial";
			case MethodHandleInfo.REF_newInvokeSpecial -> "newInvokeSpecial";
			case MethodHandleInfo.REF_invokeInterface -> null; //For interfaces (duh)
			default -> "UNKNOWN: " + lambda.getImplMethodKind();
		};
		
		
		if(errName != null){
			throw new IllegalArgumentException(
				"ref must be a method reference! Eg: Foobar::foo but is" + implClassName + "::" + memberName + " (" + errName + ")"
			);
		}
		
		Class<?> implClass;
		try{
			implClass = Class.forName(implClassName);
		}catch(ClassNotFoundException e){
			throw new RuntimeException("Could not find " + implClassName, e);
		}
		
		//noinspection unchecked
		var struct = (Struct<T>)Struct.ofUnknown(implClass, Struct.STATE_FIELD_MAKE);
		
		if(struct.getFields().byName(memberName).match() instanceof Match.Some<IOField<T, ?>>(var field)){
			//noinspection unchecked
			return (IOField<T, V>)field;
		}
		
		throw new IllegalArgumentException("No matching fields from " + implClassName + "::" + memberName);
	}
	
	private static SerializedLambda asSerializedLambda(Serializable object){
		Objects.requireNonNull(object);
		Object replacement;
		try{
			var writeReplace = object.getClass().getDeclaredMethod("writeReplace");
			writeReplace.setAccessible(true);
			replacement = writeReplace.invoke(object);
		}catch(Throwable ex){
			throw new RuntimeException("Error serializing lambda", ex);
		}
		
		if(replacement instanceof SerializedLambda res){
			return res;
		}
		
		throw new IllegalStateException(
			"writeReplace must return a SerializedLambda: " + (replacement == null? "NULL" : replacement.getClass().getTypeName())
		);
	}
}
