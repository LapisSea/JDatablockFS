package com.lapissea.dfs.tools;

import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.access.TypeFlag;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.field.fields.NoIOField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

class BGRUtils{
	static <T extends IOInstance<T>> IOField<T, ?> floatArrayElement(T instance, int index, float[] inst){
		return IOFieldPrimitive.make(new FieldAccessor<T>(){
			@Override
			public Map<Class<? extends Annotation>, ? extends Annotation> getAnnotations(){
				return Map.of();
			}
			@Override
			public int getTypeID(){
				return TypeFlag.ID_OBJECT;
			}
			@Override
			public boolean genericTypeHasArgs(){ return false; }
			@Override
			public Struct<T> getDeclaringStruct(){
				return instance.getThisStruct();
			}
			@NotNull
			@Override
			public String getName(){
				return "[" + index + "]";
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return Float.class;
			}
			@Override
			public Object get(VarPool<T> ioPool, T instance1){
				return inst[index];
			}
			@Override
			public void set(VarPool<T> ioPool, T instance1, Object value){
				throw new UnsupportedOperationException();
			}
			@Override
			public boolean isReadOnly(){ return true; }
		});
	}
	static <T extends IOInstance<T>> NoIOField<T, String> stringArrayElement(T instance, int index, List<String> data){
		return new NoIOField<T, String>(new FieldAccessor<>(){
			@Override
			public Map<Class<? extends Annotation>, ? extends Annotation> getAnnotations(){
				return Map.of();
			}
			@Override
			public Struct<T> getDeclaringStruct(){
				return instance.getThisStruct();
			}
			@Override
			public int getTypeID(){
				return TypeFlag.ID_OBJECT;
			}
			@Override
			public boolean genericTypeHasArgs(){ return false; }
			@NotNull
			@Override
			public String getName(){
				return "[" + index + "]";
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return String.class;
			}
			@Override
			public Object get(VarPool<T> ioPool, T instance1){
				return data.get(index);
			}
			@Override
			public void set(VarPool<T> ioPool, T instance1, Object value){
				throw new UnsupportedOperationException();
			}
			@Override
			public boolean isReadOnly(){ return true; }
		}, SizeDescriptor.Unknown.of((ioPool1, prov, value) -> {
			throw new ShouldNeverHappenError();
		}));
	}
	static <T extends IOInstance<T>> BitField.NoIO<T, Enum> enumListElement(T instance, int index, List<Enum> data, Class<?> type){
		return new BitField.NoIO<T, Enum>(new FieldAccessor<>(){
			@Override
			public int getTypeID(){ return TypeFlag.ID_OBJECT; }
			@Override
			public Struct<T> getDeclaringStruct(){
				return instance.getThisStruct();
			}
			@Override
			public boolean genericTypeHasArgs(){ return false; }
			@NotNull
			@Override
			public String getName(){
				return "[" + index + "]";
			}
			@Override
			public Object get(VarPool<T> ioPool, T instance1){
				return data.get(index);
			}
			@Override
			public void set(VarPool<T> ioPool, T instance1, Object value){
				throw new UnsupportedOperationException();
			}
			@Override
			public Map<Class<? extends Annotation>, ? extends Annotation> getAnnotations(){
				return Map.of();
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return type;
			}
			@Override
			public boolean isReadOnly(){ return true; }
		}, SizeDescriptor.Fixed.of(WordSpace.BIT, EnumUniverse.ofUnknown(type).bitSize));
	}
}
