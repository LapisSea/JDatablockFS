package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public sealed class VarHandleAccessor<CTyp extends IOInstance<CTyp>> extends AbstractPrimitiveAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor<CTyp>{
		
		private final MethodHandle getter;
		private final MethodHandle setter;
		
		public Funct(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, name, genericType);
			
			getter.ifPresent(get -> validateGetter(genericType, get));
			setter.ifPresent(set -> validateSetter(genericType, set));
			
			this.getter = getter.map(AbstractPrimitiveAccessor::findParent).map(Access::makeMethodHandle).orElse(null);
			this.setter = setter.map(AbstractPrimitiveAccessor::findParent).map(Access::makeMethodHandle).orElse(null);
		}
		
		@Override
		protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactShort(ioPool, instance, value);
		}
		@Override
		protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (short)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactShort(ioPool, instance);
		}
		
		@Override
		protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactChar(ioPool, instance, value);
		}
		@Override
		protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (char)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactChar(ioPool, instance);
		}
		
		@Override
		protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactLong(ioPool, instance, value);
		}
		@Override
		protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (long)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactLong(ioPool, instance);
		}
		
		@Override
		protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactByte(ioPool, instance, value);
		}
		@Override
		protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (byte)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactByte(ioPool, instance);
		}
		
		@Override
		protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactInt(ioPool, instance, value);
		}
		@Override
		protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (int)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactInt(ioPool, instance);
		}
		
		@Override
		protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactDouble(ioPool, instance, value);
		}
		@Override
		protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (double)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactDouble(ioPool, instance);
		}
		
		@Override
		protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactFloat(ioPool, instance, value);
		}
		@Override
		protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (float)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactFloat(ioPool, instance);
		}
		
		@Override
		protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactBoolean(ioPool, instance, value);
		}
		@Override
		protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return (boolean)getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactBoolean(ioPool, instance);
		}
		
		@Override
		protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
			if(setter != null) try{
				setter.invoke(instance, value);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			super.setExactObject(ioPool, instance, value);
		}
		@Override
		protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) try{
				return getter.invoke(instance);
			}catch(Throwable e){ throw UtilL.uncheckedThrow(e); }
			return super.getExactObject(ioPool, instance);
		}
	}
	
	public static final class Ptr<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor.Funct<CTyp>{
		
		public Ptr(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name){
			super(struct, field, getter, setter, name, ChunkPointer.class);
		}
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			var num = (ChunkPointer)get(ioPool, instance);
			if(num == null){
				throw new NullPointerException("value in " + getType().getName() + "#" + getName() + " is null but ChunkPointer is a non nullable type");
			}
			return num.getValue();
		}
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, ChunkPointer.of(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		if(genericType == ChunkPointer.class){
			return new Ptr<>(struct, field, getter, setter, name);
		}else{
			if(getter.isEmpty() && setter.isEmpty()){
				return new VarHandleAccessor<>(struct, field, name, genericType);
			}
			return new VarHandleAccessor.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final VarHandle handle;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	public VarHandleAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name, genericType);
		handle = Access.makeVarHandle(field);
		annotations = Arrays.stream(field.getAnnotations()).collect(Collectors.toMap(Annotation::annotationType, a -> a));
	}
	
	@NotNull
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return (Optional<T>)Optional.ofNullable(annotations.get(annotationClass));
	}
	@Override
	public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return annotations.containsKey(annotationClass);
	}
	
	@Override
	protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		handle.set(instance, value);
	}
	@Override
	protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
		return (short)handle.get(instance);
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
	protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		handle.set(instance, value);
	}
	@Override
	protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
		return (float)handle.get(instance);
	}
	
	@Override
	protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		handle.set(instance, value);
	}
	@Override
	protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return (boolean)handle.get(instance);
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
