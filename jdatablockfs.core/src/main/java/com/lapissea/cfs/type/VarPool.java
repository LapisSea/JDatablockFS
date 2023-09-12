package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.content.BBView;
import com.lapissea.cfs.type.field.StoragePool;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.NotImplementedException;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;

public interface VarPool<T extends IOInstance<T>>{
	
	class GeneralVarArray<T extends IOInstance<T>> implements VarPool<T>{
		
		private final Struct<T> typ;
		private final byte      poolId;
		
		private final short objectsSize;
		private final short primitivesSize;
		
		private Object[] pool;
		private byte[]   primitives;
		
		GeneralVarArray(Struct<T> typ, StoragePool pool){
			poolId = (byte)pool.ordinal();
			this.typ = typ;
			
			var os = typ.poolObjectsSize;
			var ps = typ.poolPrimitivesSize;
			
			this.objectsSize = os != null? os[poolId] : 0;
			this.primitivesSize = ps != null? ps[poolId] : 0;
		}
		
		private void protectAccessor(VirtualAccessor<T> accessor){
			if(accessor.getDeclaringStruct() != typ){
				throw new IllegalArgumentException(accessor.getDeclaringStruct() + " != " + typ);
			}
		}
		private void protectAccessor(VirtualAccessor<T> accessor, Class<?>... types){
			protectAccessor(accessor);
			
			if(Arrays.stream(types).noneMatch(type -> accessor.getType() == type)){
				throw new IllegalArgumentException(accessor.getType() + " != " + Arrays.stream(types).map(Class::getName).collect(Collectors.joining(" || ", "(", ")")));
			}
		}
		private void protectAccessor(VirtualAccessor<T> accessor, Class<?> type){
			protectAccessor(accessor);
			
			if(accessor.getType() != type){
				throw new IllegalArgumentException(accessor.getType() + " != " + type);
			}
		}
		
		@Override
		public void set(VirtualAccessor<T> accessor, Object value){
			int index = accessor.getPtrIndex();
			if(index == -1){
				Objects.requireNonNull(value);
				var typ = accessor.getType();
				if(typ == long.class) setLong(accessor, switch(value){
					case Long n -> n;
					case Integer n -> n;
					case Short n -> n;
					case Byte n -> n;
					default -> throw new ClassCastException(value.getClass().getName() + " can not be converted to long");
				});
				else if(typ == int.class) setInt(accessor, switch(value){
					case Integer n -> n;
					case Short n -> n;
					case Byte n -> n;
					default -> throw new ClassCastException(value.getClass().getName() + " can not be converted to int");
				});
				else if(typ == byte.class) setByte(accessor, (Byte)value);
				else if(typ == boolean.class) setBoolean(accessor, (Boolean)value);
				else throw new NotImplementedException(typ.getName());
			}
			if(DEBUG_VALIDATION) protectAccessor(accessor);
			if(pool == null) pool = new Object[objectsSize];
			pool[index] = value;
		}
		@Override
		public Object get(VirtualAccessor<T> accessor){
			int index = accessor.getPtrIndex();
			if(index == -1){
				var typ = accessor.getType();
				if(typ == long.class) return getLong(accessor);
				if(typ == int.class) return getInt(accessor);
				if(typ == boolean.class) return getBoolean(accessor);
				if(typ == byte.class) return getByte(accessor);
				throw new NotImplementedException(typ.getName());
			}
			if(DEBUG_VALIDATION) protectAccessor(accessor);
			if(pool == null) return null;
			return pool[index];
		}
		
		@Override
		public long getLong(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, long.class, int.class);
			
			if(primitives == null) return 0;
			
			return switch(accessor.getPrimitiveSize()){
				case Long.BYTES -> BBView.readInt8(primitives, accessor.getPrimitiveOffset());
				case Integer.BYTES -> BBView.readInt4(primitives, accessor.getPrimitiveOffset());
				default -> throw new IllegalStateException();
			};
		}
		@Override
		public void setLong(VirtualAccessor<T> accessor, long value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, long.class);
			
			if(primitives == null){
				if(value == 0) return;
				primitives = new byte[primitivesSize];
			}
			BBView.writeInt8(primitives, accessor.getPrimitiveOffset(), value);
		}
		
		@Override
		public int getInt(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, int.class);
			
			if(primitives == null) return 0;
			return BBView.readInt4(primitives, accessor.getPrimitiveOffset());
		}
		@Override
		public void setInt(VirtualAccessor<T> accessor, int value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, int.class, long.class);
			
			if(primitives == null){
				if(value == 0) return;
				primitives = new byte[primitivesSize];
			}
			
			switch(accessor.getPrimitiveSize()){
				case Long.BYTES -> BBView.writeInt8(primitives, accessor.getPrimitiveOffset(), value);
				case Integer.BYTES -> BBView.writeInt4(primitives, accessor.getPrimitiveOffset(), value);
				default -> throw new IllegalStateException();
			}
		}
		
		@Override
		public boolean getBoolean(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, boolean.class);
			return getByte0(accessor) == 1;
		}
		@Override
		public void setBoolean(VirtualAccessor<T> accessor, boolean value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, boolean.class);
			setByte0(accessor, (byte)(value? 1 : 0));
		}
		
		@Override
		public byte getByte(VirtualAccessor<T> accessor){
			if(DEBUG_VALIDATION) protectAccessor(accessor, byte.class);
			return getByte0(accessor);
		}
		@Override
		public void setByte(VirtualAccessor<T> accessor, byte value){
			if(DEBUG_VALIDATION) protectAccessor(accessor, byte.class);
			setByte0(accessor, value);
		}
		
		private byte getByte0(VirtualAccessor<T> accessor){
			if(primitives == null) return 0;
			return primitives[accessor.getPrimitiveOffset()];
		}
		private void setByte0(VirtualAccessor<T> accessor, byte value){
			if(primitives == null){
				if(value == 0) return;
				primitives = new byte[primitivesSize];
			}
			primitives[accessor.getPrimitiveOffset()] = value;
		}
		
		
		@Override
		public String toString(){
			return typ.getFields()
			          .stream()
			          .map(f -> f.getVirtual(StoragePool.values()[poolId]))
			          .filter(Objects::nonNull)
			          .map(c -> c.getName() + ": " + Utils.toShortString(get(c)))
			          .collect(Collectors.joining(", ", Utils.classNameToHuman(typ.getFullName(), false) + "{", "}"));
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
