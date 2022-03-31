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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;

public class ReflectionAccessorUnsafe<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	public static class Num<CTyp extends IOInstance<CTyp>> extends ReflectionAccessorUnsafe<CTyp>{
		
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
			return new ReflectionAccessorUnsafe.Num<>(struct, field, getter, setter, name, genericType);
		}else{
			return new ReflectionAccessorUnsafe<>(struct, field, getter, setter, name, genericType);
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
	
	private final long fieldOffset;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	private final MethodHandle getter;
	private final MethodHandle setter;
	
	public ReflectionAccessorUnsafe(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		super(struct, name);
		this.fieldOffset=UNSAFE.objectFieldOffset(field);
		
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
	
	@Override
	public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_OBJECT -> UNSAFE.getObject(instance, fieldOffset);
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
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
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
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	private void setObj(CTyp instance, Object value){
		UNSAFE.putObject(instance, fieldOffset, getType().cast(value));
	}
	
	@Override
	public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (double)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_DOUBLE -> UNSAFE.getDouble(instance, fieldOffset);
					case ID_FLOAT -> UNSAFE.getFloat(instance, fieldOffset);
					case ID_OBJECT -> switch(get(ioPool, instance)){
						case Double n -> n;
						case Float n -> n;
						default -> throw classCastThrow();
					};
					default -> throw classCastThrow();
				};
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	private ClassCastException classCastThrow(){
		throw new ClassCastException(rawType.getName());
	}
	
	@Override
	public void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_DOUBLE -> UNSAFE.putDouble(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (float)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_FLOAT -> UNSAFE.getFloat(instance, fieldOffset);
					case ID_OBJECT -> (Float)get(ioPool, instance);
					default -> throw classCastThrow();
				};
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_FLOAT -> UNSAFE.putFloat(instance, fieldOffset, value);
					case ID_DOUBLE -> UNSAFE.putDouble(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (byte)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_BYTE -> UNSAFE.getByte(instance, fieldOffset);
					case ID_OBJECT -> (Byte)get(ioPool, instance);
					default -> throw classCastThrow();
				};
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_BYTE -> UNSAFE.putByte(instance, fieldOffset, value);
					case ID_INT -> UNSAFE.putInt(instance, fieldOffset, value);
					case ID_LONG -> UNSAFE.putLong(instance, fieldOffset, value);
					case ID_SHORT -> UNSAFE.putShort(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (boolean)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_BOOLEAN -> UNSAFE.getBoolean(instance, fieldOffset);
					case ID_OBJECT -> (Boolean)get(ioPool, instance);
					default -> throw classCastThrow();
				};
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_BOOLEAN -> UNSAFE.putBoolean(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (long)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_LONG -> UNSAFE.getLong(instance, fieldOffset);
					case ID_INT -> UNSAFE.getInt(instance, fieldOffset);
					case ID_SHORT -> UNSAFE.getShort(instance, fieldOffset);
					case ID_BYTE -> UNSAFE.getByte(instance, fieldOffset);
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
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_LONG -> UNSAFE.putLong(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (int)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_INT -> UNSAFE.getInt(instance, fieldOffset);
					case ID_SHORT -> UNSAFE.getShort(instance, fieldOffset);
					case ID_BYTE -> UNSAFE.getByte(instance, fieldOffset);
					case ID_OBJECT -> switch(get(ioPool, instance)){
						case Integer n -> n;
						case Short n -> n;
						case Byte n -> n;
						default -> throw classCastThrow();
					};
					default -> throw classCastThrow();
				};
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_INT -> UNSAFE.putInt(instance, fieldOffset, value);
					case ID_LONG -> UNSAFE.putLong(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public short getShort(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (short)getter.invoke(instance);
			}else{
				return switch(typeID){
					case ID_SHORT -> UNSAFE.getShort(instance, fieldOffset);
					case ID_BYTE -> UNSAFE.getByte(instance, fieldOffset);
					case ID_OBJECT -> switch(get(ioPool, instance)){
						case Short n -> n;
						case Byte n -> n;
						default -> throw classCastThrow();
					};
					default -> throw classCastThrow();
				};
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	public void setShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				switch(typeID){
					case ID_SHORT -> UNSAFE.putShort(instance, fieldOffset, value);
					case ID_INT -> UNSAFE.putInt(instance, fieldOffset, value);
					case ID_LONG -> UNSAFE.putLong(instance, fieldOffset, value);
					case ID_OBJECT -> setObj(instance, value);
					default -> throw classCastThrow();
				}
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean canBeNull(){
		return !rawType.isPrimitive();
	}
}
