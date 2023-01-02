package com.lapissea.cfs.type.field;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.lapissea.cfs.type.field.IOField.*;
import static com.lapissea.cfs.type.field.access.TypeFlag.*;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

/**
 * Stores bulk code for basic operations and information for {@link IOField}. Also provides emotional support
 */
class FieldSupport{
	static final boolean STAT_LOGGING = GlobalConfig.configFlag("logging.fieldTimes", false);
	
	private static final AtomicLong UID_COUNT = new AtomicLong();
	static long nextUID(){
		return UID_COUNT.incrementAndGet();
	}
	
	
	static <T extends IOInstance<T>> int hash(IOField<T, ?> field, VarPool<T> ioPool, T instance){
		var acc = field.getAccessor();
		var id  = acc.getTypeID();
		return switch(id){
			case ID_OBJECT -> Objects.hashCode(field.get(ioPool, instance));
			case ID_INT -> Integer.hashCode(acc.getInt(ioPool, instance));
			case ID_LONG -> Long.hashCode(acc.getLong(ioPool, instance));
			case ID_DOUBLE -> Double.hashCode(acc.getDouble(ioPool, instance));
			case ID_FLOAT -> Float.hashCode(acc.getFloat(ioPool, instance));
			case ID_SHORT -> Short.hashCode(acc.getShort(ioPool, instance));
			case ID_BYTE -> Byte.hashCode(acc.getByte(ioPool, instance));
			case ID_BOOLEAN -> Boolean.hashCode(acc.getBoolean(ioPool, instance));
			case ID_CHAR -> Character.hashCode(acc.getChar(ioPool, instance));
			default -> throw new IllegalStateException(id + "");
		};
	}
	
	static <T extends IOInstance<T>> boolean compare(IOField<T, ?> field, VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		var acc = field.getAccessor();
		var id  = acc.getTypeID();
		return switch(id){
			case ID_OBJECT -> instancesEqualObject(field, ioPool1, inst1, ioPool2, inst2);
			case ID_INT -> acc.getInt(ioPool1, inst1) == acc.getInt(ioPool2, inst2);
			case ID_LONG -> acc.getLong(ioPool1, inst1) == acc.getLong(ioPool2, inst2);
			case ID_DOUBLE -> acc.getDouble(ioPool1, inst1) == acc.getDouble(ioPool2, inst2);
			case ID_FLOAT -> acc.getFloat(ioPool1, inst1) == acc.getFloat(ioPool2, inst2);
			case ID_SHORT -> acc.getShort(ioPool1, inst1) == acc.getShort(ioPool2, inst2);
			case ID_BYTE -> acc.getByte(ioPool1, inst1) == acc.getByte(ioPool2, inst2);
			case ID_BOOLEAN -> acc.getBoolean(ioPool1, inst1) == acc.getBoolean(ioPool2, inst2);
			case ID_CHAR -> acc.getChar(ioPool1, inst1) == acc.getChar(ioPool2, inst2);
			default -> throw new IllegalStateException(id + "");
		};
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>, ValueType> boolean instancesEqualObject(IOField<T, ?> field, VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		var acc  = field.getAccessor();
		var type = acc.getType();
		
		var o1 = field.get(ioPool1, inst1);
		var o2 = field.get(ioPool2, inst2);
		
		if(field.getNullability() == DEFAULT_IF_NULL && (o1 == null || o2 == null)){
			if(o1 == null && o2 == null) return true;
			
			if(IOInstance.isInstance(type)){
				var struct = Struct.ofUnknown(type);
				if(o1 == null) o1 = (ValueType)struct.make();
				else o2 = (ValueType)struct.make();
			}else{
				throw new NotImplementedException(acc.getType() + "");//TODO implement equals of numbers?
			}
		}
		
		var isArray = type.isArray();
		if(!isArray && field.typeFlag(DYNAMIC_FLAG)){
			var obj = o1 != null? o1 : o2;
			isArray = obj != null && obj.getClass().isArray();
		}
		if(isArray){
			if(o1 == o2) return true;
			if(o1 == null || o2 == null) return false;
			int l1 = Array.getLength(o1);
			int l2 = Array.getLength(o2);
			if(l1 != l2) return false;
			return switch(o1){
				case byte[] arr -> Arrays.equals(arr, (byte[])o2);
				case short[] arr -> Arrays.equals(arr, (short[])o2);
				case int[] arr -> Arrays.equals(arr, (int[])o2);
				case long[] arr -> Arrays.equals(arr, (long[])o2);
				case float[] arr -> Arrays.equals(arr, (float[])o2);
				case double[] arr -> Arrays.equals(arr, (double[])o2);
				case char[] arr -> Arrays.equals(arr, (char[])o2);
				case boolean[] arr -> Arrays.equals(arr, (boolean[])o2);
				case Object[] arr -> Arrays.equals(arr, (Object[])o2);
				default -> throw new NotImplementedException(o1.getClass().getName());
			};
		}
		
		return Objects.equals(o1, o2);
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	static <T extends IOInstance<T>> Optional<String> instanceToString(IOField<T, ?> field, VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		var val = field.get(ioPool, instance);
		if(val == null){
			if(field.getNullability() == IONullability.Mode.NOT_NULL){
				throw new FieldIsNullException(field);
			}
			return Optional.empty();
		}
		
		if(val instanceof IOInstance inst){
			if("{".equals(start) && "}".equals(end) && "=".equals(fieldValueSeparator) && ", ".equals(fieldSeparator)){
				defaultStr:
				try{
					var    t = inst.getClass();
					Method strMethod;
					if(doShort){
						try{
							strMethod = t.getMethod("toShortString");
						}catch(ReflectiveOperationException e){
							strMethod = t.getMethod("toString");
						}
					}else{
						strMethod = t.getMethod("toString");
					}
					var declaring = strMethod.getDeclaringClass();
					if(declaring == IOInstance.class){
						break defaultStr;
					}
					
					return Optional.ofNullable((String)strMethod.invoke(inst));
				}catch(ReflectiveOperationException ignored){ }
			}
			
			var struct = inst.getThisStruct();
			return Optional.of(struct.instanceToString(inst, doShort, start, end, fieldValueSeparator, fieldSeparator));
		}
		
		return Optional.of(
			doShort?
			Utils.toShortString(val) :
			TextUtil.toString(val)
		);
	}
	
	static int typeFlags(IOField<?, ?> field){
		int typeFlags = 0;
		var accessor  = field.getAccessor();
		
		if(accessor != null){
			if(IOFieldTools.isGenerated(field)){
				typeFlags |= HAS_GENERATED_NAME;
			}
			
			boolean isDynamic = IOFieldTools.isGeneric(accessor);
			if(isDynamic){
				typeFlags |= DYNAMIC_FLAG;
			}
			
			var typeGen = accessor.getGenericType(null);
			while(true){
				if(typeGen instanceof Class<?> c){
					if(c.isArray()){
						typeGen = c.componentType();
						continue;
					}
				}
				if(UtilL.instanceOf(Utils.typeToRaw(typeGen), List.class)){
					typeGen = switch(typeGen){
						case Class<?> c -> typeGen = Object.class;
						case ParameterizedType t -> t.getActualTypeArguments()[0];
						default -> throw new NotImplementedException(typeGen.getClass() + "");
					};
					continue;
				}
				break;
			}
			
			var type = Utils.typeToRaw(typeGen);
			
			if(IOInstance.isInstance(type)){
				typeFlags |= IOINSTANCE_FLAG;
				
				if(!isDynamic && !(field instanceof RefField) && !Struct.canUnknownHavePointers(type)){
					typeFlags |= HAS_NO_POINTERS_FLAG;
				}
			}
			if(SupportedPrimitive.isAny(type) || type.isEnum()){
				typeFlags |= PRIMITIVE_OR_ENUM_FLAG;
			}
		}
		return typeFlags;
	}
}
