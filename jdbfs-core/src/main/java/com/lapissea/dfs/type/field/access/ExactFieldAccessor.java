package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.function.ConsumerOBool;
import com.lapissea.dfs.utils.function.ConsumerOByt;
import com.lapissea.dfs.utils.function.ConsumerOC;
import com.lapissea.dfs.utils.function.ConsumerOD;
import com.lapissea.dfs.utils.function.ConsumerOF;
import com.lapissea.dfs.utils.function.ConsumerOI;
import com.lapissea.dfs.utils.function.ConsumerOL;
import com.lapissea.dfs.utils.function.ConsumerOS;
import com.lapissea.dfs.utils.function.FunctionOBool;
import com.lapissea.dfs.utils.function.FunctionOByt;
import com.lapissea.dfs.utils.function.FunctionOC;
import com.lapissea.dfs.utils.function.FunctionOD;
import com.lapissea.dfs.utils.function.FunctionOF;
import com.lapissea.dfs.utils.function.FunctionOI;
import com.lapissea.dfs.utils.function.FunctionOL;
import com.lapissea.dfs.utils.function.FunctionOS;
import com.lapissea.util.ShouldNeverHappenError;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.lapissea.dfs.type.field.access.TypeFlag.*;

/**
 * The {@link ExactFieldAccessor} class provides a simplified interface for exact access of values.
 * The {@link FieldAccessor} normally must handle conversions of primitives and boxed types and also must handle data widening.
 * This class provides the necessary conversions and provides a set of exact access functions that just have to handle the correct
 * types. For example, if an {@link Integer} type is passed to the {@link FieldAccessor#set} function of a {@code long} accessor, then this class will unbox the
 * {@link Integer} in to an int and widen it to a long. This value will then be passed on to the {@link ExactFieldAccessor#setExactLong} function
 */
public abstract class ExactFieldAccessor<CTyp extends IOInstance<CTyp>> extends BasicFieldAccessor<CTyp>{
	
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
	private final boolean  genericTypeHasArgs;
	private final boolean  readOnlyField;
	
	public ExactFieldAccessor(Struct<CTyp> struct, String name, Type genericType, Map<Class<? extends Annotation>, ? extends Annotation> annotations, boolean readOnlyField){
		super(struct, name, annotations);
		this.readOnlyField = readOnlyField;
		
		Class<?> type = Utils.typeToRaw(genericType);
		
		typeID = TypeFlag.getId(type);
		
		this.genericType = genericType;
		this.rawType = Utils.typeToRaw(this.genericType);
		genericTypeHasArgs = IOFieldTools.doesTypeHaveArgs(genericType);
	}
	
	protected final void checkReadOnlyField(){
		if(readOnlyField){
			failReadOnly();
		}
	}
	private void failReadOnly(){
		throw new UnsupportedOperationException(Log.fmt("Field {}#red is final, can not set it!", this));
	}
	@Override
	public final boolean isReadOnly(){ return readOnlyField; }
	
	@Override
	public boolean genericTypeHasArgs(){
		return genericTypeHasArgs;
	}
	
	@SuppressWarnings("unchecked")
	protected final Function<CTyp, Object> makeGetter(Method m){
		var typ = m.getReturnType();
		if(typ == short.class) return Access.makeLambda(m, FunctionOS.class);
		if(typ == char.class) return Access.makeLambda(m, FunctionOC.class);
		if(typ == long.class) return Access.makeLambda(m, FunctionOL.class);
		if(typ == byte.class) return Access.makeLambda(m, FunctionOByt.class);
		if(typ == int.class) return Access.makeLambda(m, FunctionOI.class);
		if(typ == double.class) return Access.makeLambda(m, FunctionOD.class);
		if(typ == float.class) return Access.makeLambda(m, FunctionOF.class);
		if(typ == boolean.class) return Access.makeLambda(m, FunctionOBool.class);
		return Access.makeLambda(m, Function.class);
	}
	@SuppressWarnings("unchecked")
	protected final BiConsumer<CTyp, Object> makeSetter(Method m){
		var typ = m.getParameterTypes()[0];
		if(typ == short.class) return Access.makeLambda(m, ConsumerOS.class);
		if(typ == char.class) return Access.makeLambda(m, ConsumerOC.class);
		if(typ == long.class) return Access.makeLambda(m, ConsumerOL.class);
		if(typ == byte.class) return Access.makeLambda(m, ConsumerOByt.class);
		if(typ == int.class) return Access.makeLambda(m, ConsumerOI.class);
		if(typ == double.class) return Access.makeLambda(m, ConsumerOD.class);
		if(typ == float.class) return Access.makeLambda(m, ConsumerOF.class);
		if(typ == boolean.class) return Access.makeLambda(m, ConsumerOBool.class);
		return Access.makeLambda(m, BiConsumer.class);
	}
	
	@Override
	public final Class<?> getType(){
		return rawType;
	}
	@Override
	public final int getTypeID(){
		return typeID;
	}
	
	@Override
	public final Type getGenericType(GenericContext genericContext){
		if(genericContext == null || !genericTypeHasArgs){
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
	public final Object get(VarPool<CTyp> ioPool, CTyp instance){
		if(typeID == ID_OBJECT){
			return getExactObject(ioPool, instance);
		}else{
			return getPrimitiveAsObject(ioPool, instance);
		}
	}
	private Object getPrimitiveAsObject(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_INT -> getExactInt(ioPool, instance);
			case ID_LONG -> getExactLong(ioPool, instance);
			case ID_FLOAT -> getExactFloat(ioPool, instance);
			case ID_DOUBLE -> getExactDouble(ioPool, instance);
			case ID_BOOLEAN -> getExactBoolean(ioPool, instance);
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_SHORT -> getExactShort(ioPool, instance);
			case ID_CHAR -> getExactChar(ioPool, instance);
			default -> throw new ShouldNeverHappenError();
		};
	}
	
	@Override
	public final void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
		if(typeID == ID_OBJECT){
			setExactObject(ioPool, instance, value);
		}else{
			setObjectToPrimitive(ioPool, instance, value);
		}
	}
	
	private void setObjectToPrimitive(VarPool<CTyp> ioPool, CTyp instance, Object value){
		switch(typeID){
			case ID_INT -> setExactInt(ioPool, instance, switch(value){
				case Integer v -> v;
				case Short v -> v;
				case Byte v -> v;
				case null -> throw new NullPointerException();
				default -> throw classCastThrow();
			});
			case ID_LONG -> setExactLong(ioPool, instance, switch(value){
				case Long v -> v;
				case Integer v -> v;
				case Short v -> v;
				case Byte v -> v;
				case null -> throw new NullPointerException();
				default -> throw classCastThrow();
			});
			case ID_FLOAT -> setExactFloat(ioPool, instance, (Float)value);
			case ID_DOUBLE -> setExactDouble(ioPool, instance, switch(value){
				case Double v -> v;
				case Float v -> v;
				case null -> throw new NullPointerException();
				default -> throw classCastThrow();
			});
			case ID_BOOLEAN -> setExactBoolean(ioPool, instance, (Boolean)value);
			case ID_BYTE -> setExactByte(ioPool, instance, (Byte)value);
			case ID_SHORT -> setExactShort(ioPool, instance, switch(value){
				case Short v -> v;
				case Byte v -> v;
				case null -> throw new NullPointerException();
				default -> throw classCastThrow();
			});
			case ID_CHAR -> setExactChar(ioPool, instance, (Character)value);
			default -> throw new ShouldNeverHappenError();
		}
	}
	
	@Override
	public final double getDouble(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_DOUBLE -> getExactDouble(ioPool, instance);
			case ID_FLOAT -> getExactFloat(ioPool, instance);
			case ID_OBJECT -> getObjAsDouble(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private double getObjAsDouble(VarPool<CTyp> ioPool, CTyp instance){
		return switch(getExactObject(ioPool, instance)){
			case Double n -> n;
			case Float n -> n;
			default -> throw classCastThrow();
		};
	}
	@Override
	public final void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		switch(typeID){
			case ID_DOUBLE -> setExactDouble(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public final float getFloat(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_FLOAT -> getExactFloat(ioPool, instance);
			case ID_OBJECT -> getObjAsFloat(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private float getObjAsFloat(VarPool<CTyp> ioPool, CTyp instance){
		return (Float)getExactObject(ioPool, instance);
	}
	@Override
	public final void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		switch(typeID){
			case ID_FLOAT -> setExactFloat(ioPool, instance, value);
			case ID_DOUBLE -> setExactDouble(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public final byte getByte(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> getObjAsByte(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private byte getObjAsByte(VarPool<CTyp> ioPool, CTyp instance){
		return (Byte)getExactObject(ioPool, instance);
	}
	@Override
	public final void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
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
	public final boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_BOOLEAN -> getExactBoolean(ioPool, instance);
			case ID_OBJECT -> getObjAsBoolean(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private boolean getObjAsBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return (Boolean)getExactObject(ioPool, instance);
	}
	@Override
	public final void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
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
			case ID_OBJECT -> getObjAsLong(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private long getObjAsLong(VarPool<CTyp> ioPool, CTyp instance){
		return switch(getExactObject(ioPool, instance)){
			case Long n -> n;
			case Integer n -> n;
			case Short n -> n;
			case Byte n -> n;
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
	public final int getInt(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_INT -> getExactInt(ioPool, instance);
			case ID_SHORT -> getExactShort(ioPool, instance);
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> getObjAsInt(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private int getObjAsInt(VarPool<CTyp> ioPool, CTyp instance){
		return switch(getExactObject(ioPool, instance)){
			case Integer n -> n;
			case Short n -> n;
			case Byte n -> n;
			default -> throw classCastThrow();
		};
	}
	@Override
	public final void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		switch(typeID){
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	
	@Override
	public final short getShort(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_SHORT -> getExactShort(ioPool, instance);
			case ID_BYTE -> getExactByte(ioPool, instance);
			case ID_OBJECT -> getObjAsShort(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private short getObjAsShort(VarPool<CTyp> ioPool, CTyp instance){
		return switch(getExactObject(ioPool, instance)){
			case Short n -> n;
			case Byte n -> n;
			default -> throw classCastThrow();
		};
	}
	@Override
	public final void setShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		switch(typeID){
			case ID_SHORT -> setExactShort(ioPool, instance, value);
			case ID_INT -> setExactInt(ioPool, instance, value);
			case ID_LONG -> setExactLong(ioPool, instance, value);
			case ID_OBJECT -> setExactObject(ioPool, instance, value);
			default -> throw classCastThrow();
		}
	}
	@Override
	public final char getChar(VarPool<CTyp> ioPool, CTyp instance){
		return switch(typeID){
			case ID_CHAR -> getExactChar(ioPool, instance);
			case ID_OBJECT -> getObjAsChar(ioPool, instance);
			default -> throw classCastThrow();
		};
	}
	private char getObjAsChar(VarPool<CTyp> ioPool, CTyp instance){
		return switch(getExactObject(ioPool, instance)){
			case Character n -> n;
			default -> throw classCastThrow();
		};
	}
	@Override
	public final void setChar(VarPool<CTyp> ioPool, CTyp instance, char value){
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
