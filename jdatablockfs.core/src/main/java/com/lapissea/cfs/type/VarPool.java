package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.NotImplementedException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public interface VarPool<T extends IOInstance<T>>{
	
	class GeneralVarArray<T extends IOInstance<T>> implements VarPool<T>{
		
		private final Struct<T> typ;
		private final byte      poolId;
		
		private final short objectsSize;
		private final short primitivesSize;
		
		private Object[] pool;
		private byte[]   primitives;
		
		GeneralVarArray(Struct<T> typ, VirtualFieldDefinition.StoragePool pool){
			poolId=(byte)pool.ordinal();
			this.typ=typ;
			
			var os=typ.poolObjectsSize;
			var ps=typ.poolPrimitivesSize;
			
			this.objectsSize=os!=null?os[poolId]:0;
			this.primitivesSize=ps!=null?ps[poolId]:0;
		}
		
		private void protectAccessor(VirtualAccessor<T> accessor){
			if(accessor.getDeclaringStruct()!=typ){
				throw new IllegalArgumentException(accessor.getDeclaringStruct()+" != "+typ);
			}
		}
		private void protectAccessor(VirtualAccessor<T> accessor, List<Class<?>> types){
			protectAccessor(accessor);
			
			if(types.stream().noneMatch(type->accessor.getType()==type)){
				throw new IllegalArgumentException(accessor.getType()+" != "+types.stream().map(Class::getName).collect(Collectors.joining(" || ", "(", ")")));
			}
		}
		
		@Override
		public void set(VirtualAccessor<T> accessor, Object value){
			int index=accessor.getPtrIndex();
			if(index==-1){
				Objects.requireNonNull(value);
				var typ=accessor.getType();
				if(typ==long.class) setLong(accessor, switch(value){
					case Long n -> n;
					case Integer n -> n;
					case Short n -> n;
					case Byte n -> n;
					default -> throw new ClassCastException(value.getClass().getName()+" can not be converted to long");
				});
				else if(typ==int.class) setInt(accessor, switch(value){
					case Integer n -> n;
					case Short n -> n;
					case Byte n -> n;
					default -> throw new ClassCastException(value.getClass().getName()+" can not be converted to int");
				});
				else if(typ==byte.class) setByte(accessor, (Byte)value);
				else if(typ==boolean.class) setBoolean(accessor, (Boolean)value);
				else throw new NotImplementedException(typ.getName());
			}
			if(DEBUG_VALIDATION) protectAccessor(accessor);
			if(pool==null) pool=new Object[objectsSize];
			pool[index]=value;
		}
		@Override
		public Object get(VirtualAccessor<T> accessor){
			int index=accessor.getPtrIndex();
			if(index==-1){
				var typ=accessor.getType();
				if(typ==long.class) return getLong(accessor);
				if(typ==int.class) return getInt(accessor);
				if(typ==boolean.class) return getBoolean(accessor);
				if(typ==byte.class) return getByte(accessor);
				throw new NotImplementedException(typ.getName());
			}
			if(DEBUG_VALIDATION) protectAccessor(accessor);
			if(pool==null) return null;
			return pool[index];
		}
		
		@Override
		public long getLong(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(long.class, int.class));
			
			if(primitives==null) return 0;
			
			return switch(accessor.getPrimitiveSize()){
				case Long.BYTES -> MemPrimitive.getLong(primitives, accessor.getPrimitiveOffset());
				case Integer.BYTES -> MemPrimitive.getInt(primitives, accessor.getPrimitiveOffset());
				default -> throw new IllegalStateException();
			};
		}
		@Override
		public void setLong(VirtualAccessor<T> accessor, long value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(long.class));
			
			if(primitives==null){
				if(value==0) return;
				primitives=new byte[primitivesSize];
			}
			MemPrimitive.setLong(primitives, accessor.getPrimitiveOffset(), value);
		}
		
		@Override
		public int getInt(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(int.class));
			
			if(primitives==null) return 0;
			return MemPrimitive.getInt(primitives, accessor.getPrimitiveOffset());
		}
		@Override
		public void setInt(VirtualAccessor<T> accessor, int value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(int.class, long.class));
			
			if(primitives==null){
				if(value==0) return;
				primitives=new byte[primitivesSize];
			}
			
			switch(accessor.getPrimitiveSize()){
				case Long.BYTES -> MemPrimitive.setLong(primitives, accessor.getPrimitiveOffset(), value);
				case Integer.BYTES -> MemPrimitive.setInt(primitives, accessor.getPrimitiveOffset(), value);
				default -> throw new IllegalStateException();
			}
		}
		
		@Override
		public boolean getBoolean(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(boolean.class));
			return getByte0(accessor)==1;
		}
		@Override
		public void setBoolean(VirtualAccessor<T> accessor, boolean value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(boolean.class));
			setByte0(accessor, (byte)(value?1:0));
		}
		
		@Override
		public byte getByte(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(byte.class));
			return getByte0(accessor);
		}
		@Override
		public void setByte(VirtualAccessor<T> accessor, byte value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(byte.class));
			setByte0(accessor, value);
		}
		
		private byte getByte0(VirtualAccessor<T> accessor){
			if(primitives==null) return 0;
			return MemPrimitive.getByte(primitives, accessor.getPrimitiveOffset());
		}
		private void setByte0(VirtualAccessor<T> accessor, byte value){
			if(primitives==null){
				if(value==0) return;
				primitives=new byte[primitivesSize];
			}
			MemPrimitive.setByte(primitives, accessor.getPrimitiveOffset(), value);
		}
		
		
		@Override
		public String toString(){
			return typ.getFields()
			          .stream()
			          .map(IOField::getAccessor)
			          .filter(f->f instanceof VirtualAccessor<T> acc&&acc.getStoragePool().ordinal()==poolId)
			          .map(f->(VirtualAccessor<T>)f)
			          .map(c->c.getName()+": "+Utils.toShortString(get(c)))
			          .collect(Collectors.joining(", ", Utils.classNameToHuman(typ.getType().getName(), false)+"{", "}"));
		}
	}
	
	void set(VirtualAccessor<T> accessor, Object value);
	Object get(VirtualAccessor<T> accessor);
	
	long getLong(VirtualAccessor<T> accessor);
	void setLong(VirtualAccessor<T> accessor, long value);
	
	int getInt(VirtualAccessor<T> accessor);
	void setInt(VirtualAccessor<T> accessor, int value);
	
	boolean getBoolean(VirtualAccessor<T> accessor);
	void setBoolean(VirtualAccessor<T> accessor, boolean value);
	
	byte getByte(VirtualAccessor<T> accessor);
	void setByte(VirtualAccessor<T> accessor, byte value);
}
