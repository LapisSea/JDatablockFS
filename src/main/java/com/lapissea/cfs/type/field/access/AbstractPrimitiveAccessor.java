package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.ShouldNeverHappenError;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Map;

public abstract class AbstractPrimitiveAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
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
	
	public AbstractPrimitiveAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name);
		
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
		
		this.genericType=Utils.prottectFromVarType(genericType);
		this.rawType=Utils.typeToRaw(this.genericType);
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
	
	protected abstract void setShort(CTyp instance, short value);
	protected abstract short getShort(CTyp instance);
	
	protected abstract long getLong(CTyp instance);
	protected abstract void setLong(CTyp instance, long value);
	
	protected abstract byte getByte(CTyp instance);
	protected abstract void setByte(CTyp instance, byte value);
	
	protected abstract int getInt(CTyp instance);
	protected abstract void setInt(CTyp instance, int value);
	
	protected abstract double getDouble(CTyp instance);
	protected abstract void setDouble(CTyp instance, double value);
	
	protected abstract void setFloat(CTyp instance, float value);
	protected abstract float getFloat(CTyp instance);
	
	protected abstract void setBoolean(CTyp instance, boolean value);
	protected abstract boolean getBoolean(CTyp instance);
	
	protected abstract Object getObj(CTyp instance);
	protected abstract void setObj(CTyp instance, Object value);
	
	
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
