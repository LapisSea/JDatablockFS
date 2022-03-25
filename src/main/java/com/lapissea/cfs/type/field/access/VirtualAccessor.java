package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.VirtualFieldDefinition.GetterFilter;
import com.lapissea.util.NotImplementedException;
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
	
	private static final int OBJECT_FLAG=0;
	private static final int INT_FLAG   =1;
	private static final int LONG_FLAG  =2;
	
	private static final Function<IOInstance<?>, Struct.Pool<?>> GETTER;
	
	static{
		try{
			var fun=IOInstance.class.getDeclaredMethod("getVirtualPool");
			GETTER=Access.makeLambda(fun, Function.class);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct.Pool<T> getVirtualPool(T instance){
		var pool=(Struct.Pool<T>)GETTER.apply(instance);
		if(pool==null) throw new NullPointerException("Tried to access instance pool where there is none");
		return pool;
	}
	
	private final VirtualFieldDefinition<CTyp, Object> type;
	private final GetterFilter<CTyp, Object>           filter;
	private       List<FieldAccessor<CTyp>>            deps;
	
	private final int typeFlag;
	private final int ptrIndex;
	private final int primitiveOffset;
	private final int primitiveSize;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, int ptrIndex, int primitiveOffset, int primitiveSize){
		super(struct, type.getName());
		this.type=type;
		this.ptrIndex=ptrIndex;
		this.primitiveOffset=primitiveOffset;
		this.primitiveSize=primitiveSize;
		
		filter=type.getGetFilter();
		typeFlag=calcTypeFlag(type);
	}
	
	private int calcTypeFlag(VirtualFieldDefinition<CTyp, Object> type){
		if(type.getType() instanceof Class<?> c&&c.isPrimitive()){
			if(c==int.class) return INT_FLAG;
			if(c==long.class) return LONG_FLAG;
			throw new NotImplementedException(type.getType()+"");
		}
		return OBJECT_FLAG;
		
	}
	
	public int getPtrIndex(){
		return ptrIndex;
	}
	
	public int getPrimitiveOffset(){
		return primitiveOffset;
	}
	
	public int getPrimitiveSize(){
		return primitiveSize;
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
		var pool  =getTargetPool(ioPool, instance, true);
		var rawVal=pool==null?null:pool.get(this);
		if(filter==null) return rawVal;
		return filter.filter(ioPool, instance, deps, rawVal);
	}
	
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		getTargetPool(ioPool, instance).set(this, value);
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
		if(typeFlag==LONG_FLAG){
			return getLongUnsafe(ioPool, instance);
		}
		if(typeFlag==INT_FLAG){
			return getIntUnsafe(ioPool, instance);
		}
		
		throw new ClassCastException(type.getType().getTypeName()+" can not be converted to long");
	}
	
	@Override
	public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
		if(typeFlag!=LONG_FLAG){
			throw new ClassCastException(type.getType().getTypeName()+" can not be set to long");
		}
		getTargetPool(ioPool, instance).setLong(this, value);
	}
	
	@Override
	public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		if(typeFlag==INT_FLAG){
			return getIntUnsafe(ioPool, instance);
		}
		throw new ClassCastException(type.getType().getTypeName()+" can not be converted to int");
	}
	
	@Override
	public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
		if(typeFlag!=INT_FLAG){
			throw new ClassCastException(type.getType().getTypeName()+" can not be set to int");
		}
		getTargetPool(ioPool, instance).setInt(this, value);
	}
	
	@SuppressWarnings("unchecked")
	private long getLongUnsafe(Struct.Pool<CTyp> ioPool, CTyp instance){
		long rawVal=getTargetPool(ioPool, instance).getLong(this);
		if(filter==null) return rawVal;
		return ((GetterFilter.L<CTyp>)(Object)filter).filterPrimitive(ioPool, instance, deps, rawVal);
	}
	@SuppressWarnings("unchecked")
	private int getIntUnsafe(Struct.Pool<CTyp> ioPool, CTyp instance){
		int rawVal=getTargetPool(ioPool, instance).getInt(this);
		if(filter==null) return rawVal;
		return ((GetterFilter.I<CTyp>)(Object)filter).filterPrimitive(ioPool, instance, deps, rawVal);
	}
	
	private Struct.Pool<CTyp> getTargetPool(Struct.Pool<CTyp> ioPool, CTyp instance){
		return getTargetPool(ioPool, instance, false);
	}
	private Struct.Pool<CTyp> getTargetPool(Struct.Pool<CTyp> ioPool, CTyp instance, boolean retNull){
		return switch(getStoragePool()){
			case INSTANCE -> getVirtualPool(instance);
			case IO -> {
				if(ioPool!=null){
					yield ioPool;
				}else{
					if(retNull) yield null;
					throw new IllegalStateException(this+" is an IO pool accessor. IO pool must be provided for access");
				}
			}
		};
	}
	
	@Override
	protected String strName(){
		var index=getPtrIndex();
		return getStoragePool().shortName+(index==-1?"":index)+"("+getName()+")";
	}
}
