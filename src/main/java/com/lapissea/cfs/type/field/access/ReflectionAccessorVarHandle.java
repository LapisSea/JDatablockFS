package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.ShouldNeverHappenError;
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
import java.util.function.LongFunction;
import java.util.stream.Collectors;

public sealed class ReflectionAccessorVarHandle<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends ReflectionAccessorVarHandle<CTyp>{
		
		private final MethodHandle getter;
		private final MethodHandle setter;
		
		public Funct(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, name, genericType);
			
			getter.ifPresent(get->{
				if(!Utils.genericInstanceOf(get.getGenericReturnType(), genericType)){
					throw new MalformedStructLayout("getter returns "+get.getGenericReturnType()+" but "+genericType+" is required\n"+get);
				}
				if(get.getParameterCount()!=0){
					throw new MalformedStructLayout("getter must not have arguments\n"+get);
				}
			});
			
			setter.ifPresent(set->{
				if(!Utils.genericInstanceOf(set.getReturnType(), Void.TYPE)){
					throw new MalformedStructLayout("setter returns "+set.getReturnType()+" but "+genericType+" is required\n"+set);
				}
				if(set.getParameterCount()!=1){
					throw new MalformedStructLayout("setter must have 1 argument of "+genericType+"\n"+set);
				}
				if(!Utils.genericInstanceOf(set.getGenericParameterTypes()[0], genericType)){
					throw new MalformedStructLayout("setter argument is "+set.getGenericParameterTypes()[0]+" but "+genericType+" is required\n"+set);
				}
			});
			
			this.getter=getter.map(Access::makeMethodHandle).orElse(null);
			this.setter=setter.map(Access::makeMethodHandle).orElse(null);
		}
		
		private Object getter(CTyp instance){
			try{
				return getter.invoke(instance);
			}catch(Throwable e){
				throw UtilL.uncheckedThrow(e);
			}
		}
		private void setter(CTyp instance, Object value){
			try{
				setter.invoke(instance, value);
			}catch(Throwable e){
				throw UtilL.uncheckedThrow(e);
			}
		}
		
		@Override
		public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return getter(instance);
			else return super.get(ioPool, instance);
		}
		
		@Override
		public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
			if(setter!=null) setter(instance, value);
			else super.set(ioPool, instance, value);
		}
		
		@Override
		public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (double)getter(instance);
			else return super.getDouble(ioPool, instance);
		}
		
		@Override
		public void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){
			if(setter!=null) setter(instance, value);
			else super.setDouble(ioPool, instance, value);
		}
		
		@Override
		public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (float)getter(instance);
			else return super.getFloat(ioPool, instance);
		}
		
		@Override
		public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
			if(setter!=null) setter(instance, value);
			else super.setFloat(ioPool, instance, value);
		}
		
		@Override
		public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (byte)getter(instance);
			else return super.getByte(ioPool, instance);
		}
		
		@Override
		public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
			if(setter!=null) setter(instance, value);
			else super.setByte(ioPool, instance, value);
		}
		
		@Override
		public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (boolean)getter(instance);
			else return super.getBoolean(ioPool, instance);
		}
		
		@Override
		public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter!=null) setter(instance, value);
			else super.setBoolean(ioPool, instance, value);
		}
		
		
		@Override
		public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (long)getter(instance);
			else return super.getLong(ioPool, instance);
		}
		
		@Override
		public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
			if(setter!=null) setter(instance, value);
			else super.setLong(instance, value, ioPool);
		}
		
		@Override
		public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (int)getter(instance);
			else return super.getInt(ioPool, instance);
		}
		
		@Override
		public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
			if(setter!=null) setter(instance, value);
			else super.setInt(ioPool, instance, value);
		}
		
		@Override
		public short getShort(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (Short)getter(instance);
			else return super.getShort(ioPool, instance);
		}
		
		@Override
		public void setShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value){
			if(setter!=null) setter(instance, value);
			else super.setShort(ioPool, instance, value);
		}
	}
	
	public static final class Num<CTyp extends IOInstance<CTyp>> extends ReflectionAccessorVarHandle.Funct<CTyp>{
		
		private final LongFunction<INumber> constructor;
		
		public Num(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, getter, setter, name, genericType);
			constructor=Access.findConstructor(getType(), LongFunction.class, long.class);
		}
		@Override
		public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
			var num=(INumber)get(ioPool, instance);
			if(num==null){
				throw new NullPointerException("value in "+getType().getName()+"#"+getName()+" is null but INumber is a non nullable type");
			}
			return num.getValue();
		}
		@Override
		public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
			set(ioPool, instance, constructor.apply(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		if(genericType instanceof Class<?> c&&UtilL.instanceOf(c, INumber.class)){
			return new ReflectionAccessorVarHandle.Num<>(struct, field, getter, setter, name, genericType);
		}else{
			if(getter.isEmpty()&&setter.isEmpty()){
				return new ReflectionAccessorVarHandle<>(struct, field, name, genericType);
			}
			return new ReflectionAccessorVarHandle.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private static final int ID_OBJECT =0;
	private static final int ID_DOUBLE =1;
	private static final int ID_FLOAT  =2;
	private static final int ID_BYTE   =3;
	private static final int ID_BOOLEAN=4;
	private static final int ID_LONG   =5;
	private static final int ID_INT    =6;
	private static final int ID_SHORT  =7;
	
	private final Type     genericType;
	private final Class<?> rawType;
	private final int      typeID;
	
	private final VarHandle handle;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	public ReflectionAccessorVarHandle(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name);
		this.handle=Access.makeVarHandle(field);
		
		var t=field.getType();
		if(t.isPrimitive()) typeID=Map.of(
			double.class, ID_DOUBLE,
			float.class, ID_FLOAT,
			byte.class, ID_BYTE,
			boolean.class, ID_BOOLEAN,
			long.class, ID_LONG,
			int.class, ID_INT,
			short.class, ID_SHORT).get(t);
		else typeID=ID_OBJECT;
		
		annotations=Arrays.stream(field.getAnnotations()).collect(Collectors.toMap(Annotation::annotationType, a->a));
		this.genericType=Utils.prottectFromVarType(genericType);
		this.rawType=Utils.typeToRaw(this.genericType);
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
	public Class<?> getType(){
		return rawType;
	}
	
	@Override
	public Type getGenericType(GenericContext genericContext){
		if(genericContext==null){
			return genericType;
		}
		return genericContext.resolveType(genericType);
	}
	
	private ClassCastException classCastThrow(){
		throw new ClassCastException(rawType.getName());
	}
	
	private void setShort(CTyp instance, short value){
		handle.set(instance, value);
	}
	private short getShort(CTyp instance){
		return (short)handle.get(instance);
	}
	
	private long getLong(CTyp instance){
		return (long)handle.get(instance);
	}
	private void setLong(CTyp instance, long value){
		handle.set(instance, value);
	}
	
	private byte getByte(CTyp instance){
		return (byte)handle.get(instance);
	}
	private void setByte(CTyp instance, byte value){
		handle.set(instance, value);
	}
	
	private int getInt(CTyp instance){
		return (int)handle.get(instance);
	}
	private void setInt(CTyp instance, int value){
		handle.set(instance, value);
	}
	
	private double getDouble(CTyp instance){
		return (double)handle.get(instance);
	}
	private void setDouble(CTyp instance, double value){
		handle.set(instance, value);
	}
	
	private void setFloat(CTyp instance, float value){
		handle.set(instance, value);
	}
	private float getFloat(CTyp instance){
		return (float)handle.get(instance);
	}
	
	private void setBoolean(CTyp instance, boolean value){
		handle.set(instance, value);
	}
	private boolean getBoolean(CTyp instance){
		return (boolean)handle.get(instance);
	}
	
	private Object getObj(CTyp instance){
		return handle.get(instance);
	}
	private void setObj(CTyp instance, Object value){
		handle.set(instance, getType().cast(value));
	}
	
	
	@Override
	public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_OBJECT -> getObj(instance);
			case ID_INT -> getInt(ioPool, instance);
			case ID_LONG -> getLong(ioPool, instance);
			case ID_FLOAT -> getFloat(ioPool, instance);
			case ID_DOUBLE -> getDouble(ioPool, instance);
			case ID_BOOLEAN -> getBoolean(ioPool, instance);
			case ID_BYTE -> getByte(ioPool, instance);
			case ID_SHORT -> getShort(ioPool, instance);
			default -> throw new ShouldNeverHappenError();
		};
	}
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		switch(typeID){
			case ID_OBJECT -> setObj(instance, value);
			case ID_INT -> setInt(ioPool, instance, (Integer)value);
			case ID_LONG -> setLong(instance, (Long)value, ioPool);
			case ID_FLOAT -> setFloat(ioPool, instance, (Float)value);
			case ID_DOUBLE -> setDouble(ioPool, instance, (Double)value);
			case ID_BOOLEAN -> setBoolean(ioPool, instance, (Boolean)value);
			case ID_BYTE -> setByte(ioPool, instance, (Byte)value);
			case ID_SHORT -> setShort(ioPool, instance, (Short)value);
			default -> throw new ShouldNeverHappenError();
		}
	}
	
	@Override
	public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_DOUBLE -> getDouble(instance);
			case ID_FLOAT -> getFloat(instance);
			case ID_OBJECT -> switch(get(ioPool, instance)){
				case Double n -> n;
				case Float n -> n;
				default -> throw classCastThrow();
			};
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){
		switch(typeID){
			case ID_DOUBLE -> setDouble(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_FLOAT -> getFloat(instance);
			case ID_OBJECT -> (Float)get(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
		switch(typeID){
			case ID_FLOAT -> setFloat(instance, value);
			case ID_DOUBLE -> setDouble(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BYTE -> getByte(instance);
			case ID_OBJECT -> (Byte)get(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
		switch(typeID){
			case ID_BYTE -> setByte(instance, value);
			case ID_INT -> setInt(instance, value);
			case ID_LONG -> setLong(instance, value);
			case ID_SHORT -> setShort(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BOOLEAN -> getBoolean(instance);
			case ID_OBJECT -> (Boolean)get(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
		switch(typeID){
			case ID_BOOLEAN -> setBoolean(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_LONG -> getLong(instance);
			case ID_INT -> getInt(instance);
			case ID_SHORT -> getShort(instance);
			case ID_BYTE -> getByte(instance);
			case ID_OBJECT -> switch(get(ioPool, instance)){
				case Long n -> n;
				case Integer n -> n;
				case Short n -> n;
				case Byte n -> n;
				default -> throw classCastThrow();
			};
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
		switch(typeID){
			case ID_LONG -> setLong(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_INT -> getInt(instance);
			case ID_SHORT -> getShort(instance);
			case ID_BYTE -> getByte(instance);
			case ID_OBJECT -> switch(get(ioPool, instance)){
				case Integer n -> n;
				case Short n -> n;
				case Byte n -> n;
				default -> throw classCastThrow();
			};
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
		switch(typeID){
			case ID_INT -> setInt(instance, value);
			case ID_LONG -> setLong(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public short getShort(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_SHORT -> getShort(instance);
			case ID_BYTE -> getByte(instance);
			case ID_OBJECT -> switch(get(ioPool, instance)){
				case Short n -> n;
				case Byte n -> n;
				default -> throw classCastThrow();
			};
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value){
		switch(typeID){
			case ID_SHORT -> setShort(instance, value);
			case ID_INT -> setInt(instance, value);
			case ID_LONG -> setLong(instance, value);
			case ID_OBJECT -> setObj(instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public boolean canBeNull(){
		return !rawType.isPrimitive();
	}
}
