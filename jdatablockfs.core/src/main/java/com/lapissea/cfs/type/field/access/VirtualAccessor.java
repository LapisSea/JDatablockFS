package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.VirtualFieldDefinition.GetterFilter;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class VirtualAccessor<CTyp extends IOInstance<CTyp>> extends AbstractPrimitiveAccessor<CTyp>{
	
	private static final Function<IOInstance.Managed<?>, Struct.Pool<?>> GETTER=Access.makeLambda(IOInstance.Managed.class, "getVirtualPool", Function.class);
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct.Pool<T> getVirtualPool(T instance){
		var pool=(Struct.Pool<T>)GETTER.apply((IOInstance.Managed<?>)instance);//TODO: fix this upcast. IOInstance needs to provide?
		if(pool==null){
			throw new NullPointerException("Tried to access instance pool where there is none");
		}
		return pool;
	}
	
	private final VirtualFieldDefinition<CTyp, Object> type;
	private final GetterFilter<CTyp, Object>           filter;
	private       List<FieldAccessor<CTyp>>            dependencies;
	
	private final int ptrIndex;
	private final int primitiveOffset;
	private final int primitiveSize;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, int ptrIndex, int primitiveOffset, int primitiveSize){
		super(struct, type.getName(), type.getType());
		
		boolean noPtr=ptrIndex<0;
		boolean noOff=primitiveOffset<0;
		if(noPtr){
			if(ptrIndex!=-1) throw new IllegalArgumentException("ptrIndex = "+ptrIndex);
		}
		if(noOff){
			if(primitiveOffset!=-1) throw new IllegalArgumentException("primitiveOffset = "+primitiveOffset);
		}
		
		if(noPtr==noOff){
			throw new IllegalStateException("Must provide ptr index or primitive offset");
		}
		
		this.type=type;
		this.ptrIndex=ptrIndex;
		this.primitiveOffset=primitiveOffset;
		this.primitiveSize=primitiveSize;
		
		filter=type.getGetFilter();
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
	
	public void init(IOField<CTyp, ?> field){
		if(filter!=null){
			if(dependencies!=null){
				throw new IllegalStateException();
			}
			dependencies=getDeclaringStruct()
				             .getFields()
				             .stream()
				             .filter(f->f.isDependency(field))
				             .map(IOField::getAccessor)
				             .toList();
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected long getExactLong(Struct.Pool<CTyp> ioPool, CTyp instance){
		long rawVal=getTargetPool(ioPool, instance).getLong(this);
		if(filter==null) return rawVal;
		return ((GetterFilter.L<CTyp>)(Object)filter).filterPrimitive(ioPool, instance, dependencies, rawVal);
	}
	@Override
	protected void setExactLong(Struct.Pool<CTyp> ioPool, CTyp instance, long value){
		getTargetPool(ioPool, instance).setLong(this, value);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected int getExactInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		int rawVal=getTargetPool(ioPool, instance).getInt(this);
		if(filter==null) return rawVal;
		return ((GetterFilter.I<CTyp>)(Object)filter).filterPrimitive(ioPool, instance, dependencies, rawVal);
	}
	@Override
	protected void setExactInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){getTargetPool(ioPool, instance).setInt(this, value);}
	
	@Override
	protected short getExactShort(Struct.Pool<CTyp> ioPool, CTyp instance){return (short)getExactObject(ioPool, instance);}
	@Override
	protected void setExactShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value){setExactObject(ioPool, instance, value);}
	@Override
	protected char getExactChar(Struct.Pool<CTyp> ioPool, CTyp instance){return (char)getExactObject(ioPool, instance);}
	@Override
	protected void setExactChar(Struct.Pool<CTyp> ioPool, CTyp instance, char value){setExactObject(ioPool, instance, value);}
	
	@Override
	protected byte getExactByte(Struct.Pool<CTyp> ioPool, CTyp instance){return getTargetPool(ioPool, instance).getByte(this);}
	@Override
	protected void setExactByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){getTargetPool(ioPool, instance).setByte(this, value);}
	
	@Override
	protected double getExactDouble(Struct.Pool<CTyp> ioPool, CTyp instance){return (double)getExactObject(ioPool, instance);}
	@Override
	protected void setExactDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){setExactObject(ioPool, instance, value);}
	
	@Override
	protected float getExactFloat(Struct.Pool<CTyp> ioPool, CTyp instance){return (float)getExactObject(ioPool, instance);}
	@Override
	protected void setExactFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){setExactObject(ioPool, instance, value);}
	
	@Override
	protected boolean getExactBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){return getTargetPool(ioPool, instance).getBoolean(this);}
	@Override
	protected void setExactBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){getTargetPool(ioPool, instance).setBoolean(this, value);}
	
	@Override
	protected Object getExactObject(Struct.Pool<CTyp> ioPool, CTyp instance){
		var pool  =getTargetPool(ioPool, instance, true);
		var rawVal=pool==null?null:pool.get(this);
		if(filter==null) return rawVal;
		return filter.filter(ioPool, instance, dependencies, rawVal);
	}
	@Override
	protected void setExactObject(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		getTargetPool(ioPool, instance).set(this, value);
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
		String index;
		if(getPtrIndex()!=-1) index=getPtrIndex()+"*";
		else index=primitiveOffset+"P";
		return getStoragePool().shortName+index+"("+getName()+")";
	}
}
