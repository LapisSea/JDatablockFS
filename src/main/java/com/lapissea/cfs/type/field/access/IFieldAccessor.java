package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Function;

public interface IFieldAccessor<CTyp extends IOInstance<CTyp>> extends Comparable<IFieldAccessor<CTyp>>{
	
	class Mapped<CTyp extends IOInstance<CTyp>> implements IFieldAccessor<CTyp>{
		
		private final IFieldAccessor<CTyp> source;
		
		private final Class<?>                 rawToType;
		private final Type                     toType;
		private final Function<Object, Object> mapper;
		private final Function<Object, Object> unmapper;
		
		public <From, To> Mapped(IFieldAccessor<CTyp> source, Type toType, Function<From, To> mapper, Function<To, From> unmapper){
			this.source=source;
			this.toType=toType;
			this.mapper=(Function<Object, Object>)mapper;
			this.unmapper=(Function<Object, Object>)unmapper;
			
			this.rawToType=(Class<?>)(toType instanceof ParameterizedType p?p.getRawType():toType);
		}
		
		
		@Override
		public Struct<CTyp> getDeclaringStruct(){
			return source.getDeclaringStruct();
		}
		@Override
		public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
			return source.getAnnotation(annotationClass);
		}
		@Override
		public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
			return source.hasAnnotation(annotationClass);
		}
		@Override
		public String getName(){
			return source.getName();
		}
		@Override
		public Class<?> getType(){
			return rawToType;
		}
		@Override
		public Type getGenericType(){
			return toType;
		}
		@Override
		public Object get(CTyp instance){
			return mapper.apply(source.get(instance));
		}
		@Override
		public void set(CTyp instance, Object value){
			source.set(instance, unmapper.apply(value));
		}
		@Override
		public double getDouble(CTyp instance){
			throw NotImplementedException.infer();//TODO: implement Mapped.getDouble()
		}
		@Override
		public void setDouble(CTyp instance, double value){
			throw NotImplementedException.infer();//TODO: implement Mapped.setDouble()
		}
		@Override
		public float getFloat(CTyp instance){
			throw NotImplementedException.infer();//TODO: implement Mapped.getFloat()
		}
		@Override
		public void setFloat(CTyp instance, float value){
			throw NotImplementedException.infer();//TODO: implement Mapped.setFloat()
		}
		@Override
		public byte getByte(CTyp instance){
			throw NotImplementedException.infer();//TODO: implement Mapped.getByte()
		}
		@Override
		public void setByte(CTyp instance, byte value){
			throw NotImplementedException.infer();//TODO: implement Mapped.setByte()
		}
		@Override
		public boolean getBoolean(CTyp instance){
			throw NotImplementedException.infer();//TODO: implement Mapped.getBoolean()
		}
		@Override
		public void setBoolean(CTyp instance, boolean value){
			throw NotImplementedException.infer();//TODO: implement Mapped.setBoolean()
		}
		@Override
		public long getLong(CTyp instance){
			throw NotImplementedException.infer();//TODO: implement Mapped.getLong()
		}
		@Override
		public void setLong(CTyp instance, long value){
			throw NotImplementedException.infer();//TODO: implement Mapped.setLong()
		}
		@Override
		public int getInt(CTyp instance){
			throw NotImplementedException.infer();//TODO: implement Mapped.getInt()
		}
		@Override
		public void setInt(CTyp instance, int value){
			throw NotImplementedException.infer();//TODO: implement Mapped.setInt()
		}
	}
	
	Struct<CTyp> getDeclaringStruct();
	
	@NotNull
	<T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass);
	default boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return getAnnotation(annotationClass).isPresent();
	}
	
	@NotNull
	String getName();
	
	Class<?> getType();
	Type getGenericType();
	
	Object get(CTyp instance);
	void set(CTyp instance, Object value);
	
	double getDouble(CTyp instance);
	void setDouble(CTyp instance, double value);
	
	float getFloat(CTyp instance);
	void setFloat(CTyp instance, float value);
	
	byte getByte(CTyp instance);
	void setByte(CTyp instance, byte value);
	
	boolean getBoolean(CTyp instance);
	void setBoolean(CTyp instance, boolean value);
	
	long getLong(CTyp instance);
	void setLong(CTyp instance, long value);
	
	int getInt(CTyp instance);
	void setInt(CTyp instance, int value);
	
	@Override
	default int compareTo(IFieldAccessor<CTyp> o){
		return getName().compareTo(o.getName());
	}
	
	
	default void init(IOField<CTyp, ?> field){}
}
