package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
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
	
	@SuppressWarnings("unchecked")
	private static <T> T sneakyGet(String name){
		try{
			var fun=IOInstance.class.getDeclaredMethod(name);
			fun.setAccessible(true);
			return (T)fun.invoke(null);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	static{
		GETTER=sneakyGet("getVirtualRef");
		SETTER=sneakyGet("setVirtualRef");
	}
	
	private final Struct<CTyp>                         struct;
	private final VirtualFieldDefinition<CTyp, Object> type;
	private final int                                  accessIndex;
	private       List<IFieldAccessor<CTyp>>           deps;
	private       Object[]                             ioPool;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, int accessIndex){
		this.struct=struct;
		this.type=type;
		this.accessIndex=accessIndex;
	}
	
	public void popIoPool(){
		ioPool=null;
	}
	public void pushIoPool(Object[] ioPool){
		this.ioPool=ioPool;
	}
	
	public int getAccessIndex(){
		return accessIndex;
	}
	
	public VirtualFieldDefinition.StoragePool getStoragePool(){
		return type.storagePool;
	}
	
	@Override
	public Struct<CTyp> getDeclaringStruct(){
		return struct;
	}
	
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return Optional.ofNullable(type.getAnnotations().get(annotationClass));
	}
	@Override
	public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return type.getAnnotations().isPresent(annotationClass);
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
	public Type getGenericType(GenericContext genericContext){
		return type.getType();
	}
	
	@Override
	public void init(IOField<CTyp, ?> field){
		if(type.getGetFilter()!=null){
			deps=struct.getFields().stream().filter(f->f.getDependencies().contains(field)).map(IOField::getAccessor).collect(Collectors.toList());
		}
	}
	
	@Override
	public Object get(CTyp instance){
		var rawVal=switch(getStoragePool()){
			case INSTANCE -> GETTER.apply(instance, this);
			case IO -> {
				if(ioPool==null) yield null;
				yield ioPool[getAccessIndex()];
			}
			case NONE -> null;
		};
		var filter=type.getGetFilter();
		if(filter==null){
			return rawVal;
		}
		return filter.filter(instance, deps, rawVal);
	}
	@Override
	public void set(CTyp instance, Object value){
		switch(getStoragePool()){
		case INSTANCE -> SETTER.accept(instance, this, value);
		case IO -> {
			if(ioPool!=null){
				ioPool[getAccessIndex()]=value;
			}
		}
		case NONE -> {}
		}
		
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
		var val=get(instance);
		if(val instanceof Long l) return l;
		if(val instanceof Integer l) return l;
		throw new ClassCastException(val.getClass()+" is not long");
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
		return "#("+getName()+")"+"@"+getStoragePool().shortName+(getAccessIndex()==-1?"":getAccessIndex());
	}
	
	@Override
	public String toString(){
		return struct.getType().getName()+namString();
	}
	public String toShortString(){
		return struct.getType().getSimpleName()+namString();
	}
}
