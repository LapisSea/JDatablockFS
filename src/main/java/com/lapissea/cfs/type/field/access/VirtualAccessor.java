package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VirtualAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	private static final Function<IOInstance<?>, Struct.Pool<?>> GETTER;
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct.Pool<T> getVirtualPool(T instance){return (Struct.Pool<T>)GETTER.apply(instance);}
	
	static{
		try{
			var fun=IOInstance.class.getDeclaredMethod("getVirtualPool");
			GETTER=Utils.makeLambda(fun, Function.class);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	private final VirtualFieldDefinition<CTyp, Object> type;
	private final int                                  accessIndex;
	private       List<FieldAccessor<CTyp>>            deps;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, int accessIndex){
		super(struct, type.getName());
		this.type=type;
		this.accessIndex=accessIndex;
	}
	
	public int getAccessIndex(){
		return accessIndex;
	}
	
	public VirtualFieldDefinition.StoragePool getStoragePool(){
		return type.storagePool;
	}
	
	@NotNull
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
	public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
		var rawVal=switch(getStoragePool()){
			case INSTANCE -> getVirtualPool(instance).get(this);
			case IO -> {
				if(ioPool==null) yield null;
				yield ioPool.get(this);
			}
			case NONE -> null;
		};
		var filter=type.getGetFilter();
		if(filter==null){
			return rawVal;
		}
		return filter.filter(ioPool, instance, deps, rawVal);
	}
	
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		switch(getStoragePool()){
			case INSTANCE -> getVirtualPool(instance).set(this, value);
			case IO -> {
				if(ioPool!=null){
					ioPool.set(this, value);
				}else{
					throw new IllegalStateException(this+" is an IO pool accessor. IO pool must be bound before setting");
				}
			}
			case NONE -> {}
		}
		
	}
	
	@Override
	public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
		return (double)get(ioPool, instance);
	}
	@Override
	public void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){
		set(ioPool, instance, value);
	}
	@Override
	public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
		return (float)get(ioPool, instance);
	}
	@Override
	public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
		set(ioPool, instance, value);
	}
	@Override
	public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
		return (byte)get(ioPool, instance);
	}
	@Override
	public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
		set(ioPool, instance, value);
	}
	@Override
	public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
		return (boolean)get(ioPool, instance);
	}
	@Override
	public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
		set(ioPool, instance, value);
	}
	@Override
	public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
		var val=get(ioPool, instance);
		if(val instanceof Long l) return l;
		if(val instanceof Integer l) return l;
		throw new ClassCastException(val.getClass()+" is not long");
	}
	@Override
	public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
		set(ioPool, instance, value);
	}
	@Override
	public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		return (int)get(ioPool, instance);
	}
	@Override
	public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
		set(ioPool, instance, value);
	}
	
	@Override
	protected String strName(){
		var index=getAccessIndex();
		return getStoragePool().shortName+(index==-1?"":index)+"("+getName()+")";
	}
}
