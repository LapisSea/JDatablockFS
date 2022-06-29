package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.ShouldNeverHappenError;

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
	private static final int ID_CHAR   =8;
	
	private final Type     genericType;
	private final Class<?> rawType;
	private final int      typeID;
	
	public AbstractPrimitiveAccessor(Struct<CTyp> struct, String name, Type genericType){
		super(struct, name);
		
		Class<?> type=Utils.typeToRaw(genericType);
		
		if(type.isPrimitive()) typeID=Map.of(
			double.class, ID_DOUBLE,
			float.class, ID_FLOAT,
			byte.class, ID_BYTE,
			boolean.class, ID_BOOLEAN,
			long.class, ID_LONG,
			int.class, ID_INT,
			short.class, ID_SHORT,
			char.class, ID_CHAR).get(type);
		else typeID=ID_OBJECT;
		
		this.genericType=Utils.prottectFromVarType(genericType);
		this.rawType=Utils.typeToRaw(this.genericType);
	}
	
	@Override
	public final Class<?> getType(){
		return rawType;
	}
	
	@Override
	public final Type getGenericType(GenericContext genericContext){
		if(genericContext==null){
			return genericType;
		}
		return genericContext.resolveType(genericType);
	}
	
	private ClassCastException classCastThrow(){
		throw new ClassCastException(rawType.getName());
	}
	
	protected abstract short getExactShort(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value);
	
	protected abstract char getExactChar(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactChar(Struct.Pool<CTyp> ioPool, CTyp instance, char value);
	
	protected abstract long getExactLong(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactLong(Struct.Pool<CTyp> ioPool, CTyp instance, long value);
	
	protected abstract byte getExactByte(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value);
	
	protected abstract int getExactInt(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value);
	
	protected abstract double getExactDouble(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value);
	
	protected abstract float getExactFloat(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value);
	
	protected abstract boolean getExactBoolean(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value);
	
	protected abstract Object getExactObject(Struct.Pool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactObject(Struct.Pool<CTyp> ioPool, CTyp instance, Object value);
	
	
	@Override
	public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_OBJECT -> getExactObject(ioPool, instance);
			case ID_INT -> getInt(ioPool, instance);
			case ID_LONG -> getLong(ioPool, instance);
			case ID_FLOAT -> getFloat(ioPool, instance);
			case ID_DOUBLE -> getDouble(ioPool, instance);
			case ID_BOOLEAN -> getBoolean(ioPool, instance);
			case ID_BYTE -> getByte(ioPool, instance);
			case ID_SHORT -> getShort(ioPool, instance);
			case ID_CHAR -> getChar(ioPool, instance);
			default -> throw new ShouldNeverHappenError();
		};
	}
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		switch(typeID){
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			case ID_INT -> setInt(ioPool, instance, (Integer)value);
			case ID_LONG -> setLong(ioPool, instance, (Long)value);
			case ID_FLOAT -> setFloat(ioPool, instance, (Float)value);
			case ID_DOUBLE -> setDouble(ioPool, instance, (Double)value);
			case ID_BOOLEAN -> setBoolean(ioPool, instance, (Boolean)value);
			case ID_BYTE -> setByte(ioPool, instance, (Byte)value);
			case ID_SHORT -> setShort(ioPool, instance, (Short)value);
			case ID_CHAR -> setChar(ioPool, instance, (Character)value);
			default -> throw new ShouldNeverHappenError();
		}
	}
	
	@Override
	public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_DOUBLE -> getExactDouble(ioPool, instance);
			case ID_FLOAT -> getExactFloat(ioPool, instance);
			case ID_OBJECT -> switch(getExactObject(ioPool, instance)){
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
			case ID_DOUBLE -> setExactDouble(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_FLOAT -> getExactFloat(ioPool, instance);
			case ID_OBJECT -> (Float)getExactObject(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
		switch(typeID){
			case ID_FLOAT -> setExactFloat(ioPool, instance, value);
			case ID_DOUBLE -> setExactDouble(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> (Byte)getExactObject(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
		switch(typeID){
			case ID_BYTE -> setExactByte(ioPool, instance, value);
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_SHORT -> setExactShort(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BOOLEAN -> getExactBoolean(ioPool, instance);
			case ID_OBJECT -> (Boolean)getExactObject(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
		switch(typeID){
			case ID_BOOLEAN -> setExactBoolean(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_LONG -> getExactLong(ioPool, instance);
			case ID_INT -> getExactInt(ioPool, instance);
			case ID_SHORT -> getExactShort(ioPool, instance);
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> switch(getExactObject(ioPool, instance)){
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
	public void setLong(Struct.Pool<CTyp> ioPool, CTyp instance, long value){
		switch(typeID){
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_INT -> getExactInt(ioPool, instance);
			case ID_SHORT -> getExactShort(ioPool, instance);
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> switch(getExactObject(ioPool, instance)){
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
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public short getShort(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_SHORT -> getExactShort(ioPool, instance);
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> switch(getExactObject(ioPool, instance)){
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
			case ID_SHORT -> setExactShort(ioPool, instance, value);
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	@Override
	public char getChar(Struct.Pool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_CHAR -> getExactChar(ioPool, instance);
			case ID_OBJECT -> switch(getExactObject(ioPool, instance)){
				case Character n -> n;
				default -> throw classCastThrow();
			};
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setChar(Struct.Pool<CTyp> ioPool, CTyp instance, char value){
		switch(typeID){
			case ID_CHAR -> setExactChar(ioPool, instance, value);
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	@Override
	public final boolean canBeNull(){
		return typeID==ID_OBJECT;
	}
}
