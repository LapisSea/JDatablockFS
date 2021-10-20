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

public class VirtualAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
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
	
	private final VirtualFieldDefinition<CTyp, Object> type;
	private final int                                  accessIndex;
	private       List<FieldAccessor<CTyp>>            deps;
	private       Object[]                             ioPool;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, int accessIndex){
		super(struct, type.getName());
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
			deps=getDeclaringStruct()
				.getFields()
				.stream()
				.filter(f->f.getDependencies().contains(field))
				.map(IOField::getAccessor)
				.collect(Collectors.toList());
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
			}else{
				throw new IllegalStateException(this+" is an IO pool accessor. IO pool must be bound before setting");
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
	
	@Override
	protected String strName(){
		var index=getAccessIndex();
		return getStoragePool().shortName+(index==-1?"":index)+"("+getName()+")";
	}
}
