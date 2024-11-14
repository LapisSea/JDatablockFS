package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.util.Nullable;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.Function;

public sealed class VarHandleAccessor<CTyp extends IOInstance<CTyp>> extends ExactFieldAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor<CTyp>{
		
		private final Function<CTyp, ?>        getter;
		private final BiConsumer<CTyp, Object> setter;
		
		public Funct(Struct<CTyp> struct, Field field, @Nullable Method getter, @Nullable Method setter, String name, Type genericType){
			super(struct, field, name, genericType);
			
			if(getter != null) validateGetter(genericType, getter);
			if(setter != null) validateSetter(genericType, setter);
			
			this.getter = getter != null? makeGetter(findParent(getter)) : null;
			this.setter = setter != null? makeSetter(findParent(setter)) : null;
		}
		
		@Override
		protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactShort(ioPool, instance, value);
		}
		@Override
		protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (short)getter.apply(instance);
			else return super.getExactShort(ioPool, instance);
		}
		
		@Override
		protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactChar(ioPool, instance, value);
		}
		@Override
		protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (char)getter.apply(instance);
			else return super.getExactChar(ioPool, instance);
		}
		
		@Override
		protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactLong(ioPool, instance, value);
		}
		@Override
		protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (long)getter.apply(instance);
			else return super.getExactLong(ioPool, instance);
		}
		
		@Override
		protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactByte(ioPool, instance, value);
		}
		@Override
		protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (byte)getter.apply(instance);
			else return super.getExactByte(ioPool, instance);
		}
		
		@Override
		protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactInt(ioPool, instance, value);
		}
		@Override
		protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (int)getter.apply(instance);
			else return super.getExactInt(ioPool, instance);
		}
		
		@Override
		protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactDouble(ioPool, instance, value);
		}
		@Override
		protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (double)getter.apply(instance);
			else return super.getExactDouble(ioPool, instance);
		}
		
		@Override
		protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactFloat(ioPool, instance, value);
		}
		@Override
		protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (float)getter.apply(instance);
			else return super.getExactFloat(ioPool, instance);
		}
		
		@Override
		protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactBoolean(ioPool, instance, value);
		}
		@Override
		protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (boolean)getter.apply(instance);
			else return super.getExactBoolean(ioPool, instance);
		}
		
		@Override
		protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactObject(ioPool, instance, value);
		}
		@Override
		protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return getter.apply(instance);
			else return super.getExactObject(ioPool, instance);
		}
	}
	
	public static final class PtrFunc<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor.Funct<CTyp>{
		
		public PtrFunc(Struct<CTyp> struct, Field field, Method getter, Method setter, String name){
			super(struct, field, getter, setter, name, ChunkPointer.class);
		}
		@Override
		public long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
			var num = (ChunkPointer)get(ioPool, instance);
			if(num == null) fail();
			return num.getValue();
		}
		private void fail(){
			throw new NullPointerException("value in " + getType().getName() + "#" + getName() + " is null but ChunkPointer is a non nullable type");
		}
		@Override
		public void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, ChunkPointer.of(value));
		}
	}
	
	public static final class Ptr<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor<CTyp>{
		
		public Ptr(Struct<CTyp> struct, Field field, String name){
			super(struct, field, name, ChunkPointer.class);
		}
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			var num = (ChunkPointer)get(ioPool, instance);
			if(num == null) fail();
			return num.getValue();
		}
		private void fail(){
			throw new NullPointerException("value in " + getType().getName() + "#" + getName() + " is null but ChunkPointer is a non nullable type");
		}
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, ChunkPointer.of(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Method getter, Method setter, String name, Type genericType){
		var noFn = getter == null && setter == null;
		if(genericType == ChunkPointer.class){
			if(noFn) return new Ptr<>(struct, field, name);
			return new PtrFunc<>(struct, field, getter, setter, name);
		}else{
			if(noFn) return new VarHandleAccessor<>(struct, field, name, genericType);
			return new VarHandleAccessor.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final VarHandle handle;
	
	public VarHandleAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name, genericType, IOFieldTools.computeAnnotations(field), Modifier.isFinal(field.getModifiers()));
		handle = Access.makeVarHandle(field);
	}
	
	@Override
	protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
		return (short)handle.get(instance);
	}
	@Override
	protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		handle.set(instance, value);
	}
	
	@Override
	protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
		return (char)handle.get(instance);
	}
	@Override
	protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
		handle.set(instance, value);
	}
	
	@Override
	protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
		return (long)handle.get(instance);
	}
	@Override
	protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		handle.set(instance, value);
	}
	
	@Override
	protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
		return (byte)handle.get(instance);
	}
	@Override
	protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		handle.set(instance, value);
	}
	
	@Override
	protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
		return (int)handle.get(instance);
	}
	@Override
	protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		handle.set(instance, value);
	}
	
	@Override
	protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
		return (double)handle.get(instance);
	}
	@Override
	protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		handle.set(instance, value);
	}
	
	@Override
	protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
		return (float)handle.get(instance);
	}
	@Override
	protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		handle.set(instance, value);
	}
	
	@Override
	protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return (boolean)handle.get(instance);
	}
	@Override
	protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		handle.set(instance, value);
	}
	
	@Override
	protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
		return handle.get(instance);
	}
	@Override
	protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
		handle.set(instance, getType().cast(value));
	}
}
