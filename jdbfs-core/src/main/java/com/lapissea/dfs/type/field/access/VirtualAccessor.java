package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.VirtualFieldDefinition.GetterFilter;

import java.util.List;
import java.util.function.Function;

public final class VirtualAccessor<CTyp extends IOInstance<CTyp>> extends AbstractPrimitiveAccessor<CTyp>{
	
	private static final Function<IOInstance.Managed<?>, VarPool<?>> GETTER = Access.makeLambda(IOInstance.Managed.class, "getVirtualPool", Function.class);
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> VarPool<T> getVirtualPool(T instance){
		var pool = (VarPool<T>)GETTER.apply((IOInstance.Managed<?>)instance);//TODO: fix this upcast. IOInstance needs to provide?
		if(pool == null){
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
		super(struct, type.name, type.type, type.annotations);
		
		boolean noPtr = ptrIndex<0;
		boolean noOff = primitiveOffset<0;
		if(noPtr){
			if(ptrIndex != -1) throw new IllegalArgumentException("ptrIndex = " + ptrIndex);
		}
		if(noOff){
			if(primitiveOffset != -1) throw new IllegalArgumentException("primitiveOffset = " + primitiveOffset);
		}
		
		if(noPtr == noOff){
			throw new IllegalStateException("Must provide ptr index or primitive offset");
		}
		
		this.type = type;
		this.ptrIndex = ptrIndex;
		this.primitiveOffset = primitiveOffset;
		this.primitiveSize = primitiveSize;
		
		filter = type.getFilter;
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
	
	public StoragePool getStoragePool(){
		return type.storagePool;
	}
	
	public void init(IOField<CTyp, ?> field){
		if(filter != null){
			if(dependencies != null){
				throw new IllegalStateException();
			}
			dependencies = getDeclaringStruct()
				               .getFields()
				               .stream()
				               .filter(f -> f.isDependency(field))
				               .map(IOField::getAccessor)
				               .toList();
		}
	}
	
	@Override
	protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
		long rawVal = getTargetPool(ioPool, instance).getLong(this);
		if(filter == null) return rawVal;
		return (long)filter.filter(ioPool, instance, dependencies, rawVal);
	}
	@Override
	protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		getTargetPool(ioPool, instance).setLong(this, value);
	}
	
	@Override
	protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
		int rawVal = getTargetPool(ioPool, instance).getInt(this);
		if(filter == null) return rawVal;
		return (int)filter.filter(ioPool, instance, dependencies, rawVal);
	}
	@Override
	protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){ getTargetPool(ioPool, instance).setInt(this, value); }
	
	@Override
	protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){ return (short)getExactObject(ioPool, instance); }
	@Override
	protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){ setExactObject(ioPool, instance, value); }
	@Override
	protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){ return (char)getExactObject(ioPool, instance); }
	@Override
	protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){ setExactObject(ioPool, instance, value); }
	
	@Override
	protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){ return getTargetPool(ioPool, instance).getByte(this); }
	@Override
	protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){ getTargetPool(ioPool, instance).setByte(this, value); }
	
	@Override
	protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){ return (double)getExactObject(ioPool, instance); }
	@Override
	protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){ setExactObject(ioPool, instance, value); }
	
	@Override
	protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){ return (float)getExactObject(ioPool, instance); }
	@Override
	protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){ setExactObject(ioPool, instance, value); }
	
	@Override
	protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){ return getTargetPool(ioPool, instance).getBoolean(this); }
	@Override
	protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){ getTargetPool(ioPool, instance).setBoolean(this, value); }
	
	@Override
	protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
		var pool   = getTargetPool(ioPool, instance, true);
		var rawVal = pool == null? null : pool.get(this);
		if(filter == null) return rawVal;
		return filter.filter(ioPool, instance, dependencies, rawVal);
	}
	@Override
	protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
		getTargetPool(ioPool, instance).set(this, value);
	}
	
	private VarPool<CTyp> getTargetPool(VarPool<CTyp> ioPool, CTyp instance){
		return getTargetPool(ioPool, instance, false);
	}
	private VarPool<CTyp> getTargetPool(VarPool<CTyp> ioPool, CTyp instance, boolean retNull){
		if(getStoragePool() == StoragePool.INSTANCE){
			return getVirtualPool(instance);
		}
		if(ioPool != null){
			return ioPool;
		}else{
			if(retNull) return null;
			throw failIOPool();
		}
	}
	private IllegalStateException failIOPool(){
		return new IllegalStateException(this + " is an IO pool accessor. IO pool must be provided for access");
	}
	
	@Override
	protected String strName(){
		String index;
		if(getPtrIndex() != -1) index = getPtrIndex() + "*";
		else index = primitiveOffset + "P";
		return getStoragePool().shortName + index + "(" + getName() + ")";
	}
}
