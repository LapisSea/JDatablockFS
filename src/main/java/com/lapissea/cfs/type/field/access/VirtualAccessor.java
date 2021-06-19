package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.TriConsumer;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class VirtualAccessor<CTyp extends IOInstance<CTyp>> implements IFieldAccessor<CTyp>{
	
	private static final BiFunction<IOInstance<?>, VirtualAccessor<?>, Object>  GETTER;
	private static final TriConsumer<IOInstance<?>, VirtualAccessor<?>, Object> SETTER;
	
	static{
		try{
			GETTER=Utils.makeLambda(IOInstance.class.getDeclaredMethod("accessVirtual", VirtualAccessor.class), BiFunction.class);
			SETTER=Utils.makeLambda(IOInstance.class.getDeclaredMethod("accessVirtual", VirtualAccessor.class, Object.class), TriConsumer.class);
		}catch(NoSuchMethodException e){
			throw new RuntimeException(e);
		}
	}
	
	private final Struct<CTyp>                         struct;
	private final VirtualFieldDefinition<CTyp, Object> type;
	private final int                                  accessIndex;
	private       List<IFieldAccessor<CTyp>>           deps;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, int accessIndex){
		this.struct=struct;
		this.type=type;
		this.accessIndex=accessIndex;
	}
	
	public int getAccessIndex(){
		return accessIndex;
	}
	
	@Override
	public Struct<CTyp> getStruct(){
		return struct;
	}
	
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return Optional.empty();
	}
	@Override
	public String getName(){
		return type.getName();
	}
	@Override
	public Class<?> getType(){
		return type.getType() instanceof Class<?> c?c:(Class<?>)((ParameterizedType)type.getType()).getRawType();
	}
	@Override
	public Type getGenericType(){
		return type.getType();
	}
	
	@Override
	public void init(IOField<CTyp, ?> field){
		deps=struct.getFields().stream().filter(f->f.getDependencies().contains(field)).map(IOField::getAccessor).collect(Collectors.toList());
	}
	
	@Override
	public Object get(CTyp instance){
		return type.getGetFilter().filter(instance, deps, GETTER.apply(instance, this));
	}
	@Override
	public void set(CTyp instance, Object value){
		SETTER.accept(instance, this, value);
	}
	
	@Override
	public double getDouble(CTyp instance){
		return (double)get(instance);
	}
	@Override
	public void setDouble(CTyp instance, double value){
		set(instance, value);
	}
	@Override
	public float getFloat(CTyp instance){
		return (float)get(instance);
	}
	@Override
	public void setFloat(CTyp instance, float value){
		set(instance, value);
	}
	@Override
	public byte getByte(CTyp instance){
		return (byte)get(instance);
	}
	@Override
	public void setByte(CTyp instance, byte value){
		set(instance, value);
	}
	@Override
	public boolean getBoolean(CTyp instance){
		return (boolean)get(instance);
	}
	@Override
	public void setBoolean(CTyp instance, boolean value){
		set(instance, value);
	}
	@Override
	public long getLong(CTyp instance){
		return (long)get(instance);
	}
	@Override
	public void setLong(CTyp instance, long value){
		set(instance, value);
	}
	@Override
	public int getInt(CTyp instance){
		return (int)get(instance);
	}
	@Override
	public void setInt(CTyp instance, int value){
		set(instance, value);
	}
	
	private String namString(){
		return "#("+getName()+")"+(getAccessIndex()==-1?"":"@"+getAccessIndex());
	}
	
	@Override
	public String toString(){
		return struct.getType().getName()+namString();
	}
	public String toShortString(){
		return struct.getType().getSimpleName()+namString();
	}
}
