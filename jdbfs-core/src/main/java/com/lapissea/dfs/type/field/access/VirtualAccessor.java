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
import java.util.Objects;
import java.util.function.Function;

public final class VirtualAccessor<CTyp extends IOInstance<CTyp>> extends ExactFieldAccessor<CTyp>{
	
	public sealed interface TypeOff{
		final class Ptr implements TypeOff{
			public final int index;
			public Ptr(int index){
				if(index<0){
					throw new IllegalArgumentException("index must be positive but is " + index);
				}
				this.index = index;
			}
		}
		
		final class Primitive implements TypeOff{
			public final int offset, size;
			public Primitive(int offset, int size){
				if(offset<0){
					throw new IllegalArgumentException("offset must be positive but is " + offset);
				}
				if(size<0){
					throw new IllegalArgumentException("size must be positive but is " + size);
				}
				this.offset = offset;
				this.size = size;
			}
		}
	}
	
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
	
	public final TypeOff typeOff;
	
	public VirtualAccessor(Struct<CTyp> struct, VirtualFieldDefinition<CTyp, Object> type, TypeOff typeOff){
		super(struct, type.name, type.type, type.annotations, false);
		this.type = type;
		this.typeOff = Objects.requireNonNull(typeOff);
		
		filter = type.getFilter;
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
				               .filtered(f -> f.isDependency(field))
				               .toList(IOField::getAccessor);
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
		var index = switch(typeOff){
			case TypeOff.Primitive primitive -> primitive.offset + "P";
			case TypeOff.Ptr ptr -> ptr.index + "*";
		};
		return getStoragePool().shortName + index + "(" + getName() + ")";
	}
}
