package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.util.ShouldNeverHappenError;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;

import static com.lapissea.cfs.type.field.access.TypeFlag.*;

public abstract class AbstractPrimitiveAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	protected static Method findParent(Method method){
		var cl = method.getDeclaringClass();
		for(var interf : cl.getInterfaces()){
			try{
				var mth = interf.getMethod(method.getName(), method.getParameterTypes());
				return findParent(mth);
			}catch(ReflectiveOperationException ignored){ }
		}
		var sup = cl.getSuperclass();
		if(sup != Object.class && sup != null){
			try{
				var mth = sup.getDeclaredMethod(method.getName(), method.getParameterTypes());
				return findParent(mth);
			}catch(ReflectiveOperationException ignored){ }
		}
		return method;
	}
	
	private final Type     genericType;
	private final Class<?> rawType;
	private final int      typeID;
	
	public AbstractPrimitiveAccessor(Struct<CTyp> struct, String name, Type genericType, Map<Class<? extends Annotation>, ? extends Annotation> annotations){
		super(struct, name, annotations);
		
		Class<?> type = Utils.typeToRaw(genericType);
		
		typeID = TypeFlag.getId(type);
		
		this.genericType = Utils.prottectFromVarType(genericType);
		this.rawType = Utils.typeToRaw(this.genericType);
	}
	
	@Override
	public final Class<?> getType(){
		return rawType;
	}
	@Override
	public int getTypeID(){
		return typeID;
	}
	
	@Override
	public final Type getGenericType(GenericContext genericContext){
		if(genericContext == null){
			return genericType;
		}
		return genericContext.resolveType(genericType);
	}
	
	private ClassCastException classCastThrow(){
		throw new ClassCastException(rawType.getName());
	}
	
	protected abstract short getExactShort(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value);
	
	protected abstract char getExactChar(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value);
	
	protected abstract long getExactLong(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value);
	
	protected abstract byte getExactByte(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value);
	
	protected abstract int getExactInt(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value);
	
	protected abstract double getExactDouble(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value);
	
	protected abstract float getExactFloat(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value);
	
	protected abstract boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value);
	
	protected abstract Object getExactObject(VarPool<CTyp> ioPool, CTyp instance);
	protected abstract void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value);
	
	
	@Override
	public Object get(VarPool<CTyp> ioPool, CTyp instance){
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
	public void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
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
	public double getDouble(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		switch(typeID){
			case ID_DOUBLE -> setExactDouble(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public float getFloat(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_FLOAT -> getExactFloat(ioPool, instance);
			case ID_OBJECT -> (Float)getExactObject(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		switch(typeID){
			case ID_FLOAT -> setExactFloat(ioPool, instance, value);
			case ID_DOUBLE -> setExactDouble(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public byte getByte(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> (Byte)getExactObject(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
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
	public boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BOOLEAN -> getExactBoolean(ioPool, instance);
			case ID_OBJECT -> (Boolean)getExactObject(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	@Override
	public void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		switch(typeID){
			case ID_BOOLEAN -> setExactBoolean(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public long getLong(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		switch(typeID){
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public int getInt(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		switch(typeID){
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public short getShort(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		switch(typeID){
			case ID_SHORT -> setExactShort(ioPool, instance, value);
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	@Override
	public char getChar(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setChar(VarPool<CTyp> ioPool, CTyp instance, char value){
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
		return typeID == ID_OBJECT;
	}
}
