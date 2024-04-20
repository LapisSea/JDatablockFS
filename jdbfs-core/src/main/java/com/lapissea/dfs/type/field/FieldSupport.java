package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.type.field.IOField.*;
import static com.lapissea.dfs.type.field.access.TypeFlag.*;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

/**
 * Stores bulk code for basic operations and information for {@link IOField}. Also provides emotional support
 */
final class FieldSupport{
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
	
	private static <T extends IOInstance<T>> boolean instancesEqualObject(IOField<T, ?> field, VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		var acc  = field.getAccessor();
		var type = acc.getType();
		
		var o1 = field.get(ioPool1, inst1);
		var o2 = field.get(ioPool2, inst2);
		
		if(field.getNullability() == DEFAULT_IF_NULL && (o1 == null || o2 == null)){
			if(o1 == null && o2 == null) return true;
			
			if(IOInstance.isInstance(type)){
				var struct = Struct.ofUnknown(type);
				if(o1 == null) o1 = struct.make();
				else o2 = struct.make();
			}else{
				throw new NotImplementedException(acc.getType() + "");//TODO implement equals of numbers?
			}
		}
		
		return areEqual(o1, o2);
	}
	
	private static boolean areEqual(Object o1, Object o2){
		if(o2 != null){
			if(o2.getClass().isArray()){
				return areArraysEqual(o1, o2);
			}
			if(o2 instanceof Collection<?> c2 && o1 instanceof Collection<?> c1){
				if(c2.size() != c1.size()) return false;
				var i1 = c1.iterator();
				var i2 = c2.iterator();
				while(i1.hasNext()){
					var e1 = i1.next();
					var e2 = i2.next();
					if(!areEqual(e1, e2)){
						return false;
					}
				}
				return true;
			}
		}
		
		return Objects.equals(o1, o2);
	}
	
	private static boolean areArraysEqual(Object o1, Object o2){
		if(Objects.equals(o1, o2)){
			return true;
		}
		if(null == o2 || null == o1) return false;
		
		if(o1 instanceof Object[] arr && o2 instanceof Object[] arr2){
			int len = arr.length;
			if(len != arr2.length){
				return false;
			}
			for(int i = 0; i<len; i++){
				var e1 = arr[i];
				var e2 = arr2[i];
				if(!areEqual(e1, e2)){
					return false;
				}
			}
			return true;
		}
		
		if(o1.getClass() != o2.getClass()){
			return false;
		}
		
		return switch(o1){
			case byte[] arr -> Arrays.equals(arr, (byte[])o2);
			case short[] arr -> Arrays.equals(arr, (short[])o2);
			case int[] arr -> Arrays.equals(arr, (int[])o2);
			case long[] arr -> Arrays.equals(arr, (long[])o2);
			case float[] arr -> Arrays.equals(arr, (float[])o2);
			case double[] arr -> Arrays.equals(arr, (double[])o2);
			case char[] arr -> Arrays.equals(arr, (char[])o2);
			case boolean[] arr -> Arrays.equals(arr, (boolean[])o2);
			default -> throw new NotImplementedException(o1.getClass().getName());
		};
	}
	
	private static String rem(int remaining){ return "... " + remaining + " more"; }
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	static <T extends IOInstance<T>> Optional<String> instanceToString(IOField<T, ?> field, VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		var val = field.get(ioPool, instance);
		if(val == null){
			if(field.getNullability() == IONullability.Mode.NOT_NULL){
				throw new FieldIsNull(field);
			}
			return Optional.empty();
		}
		
		if(val instanceof IOInstance inst){
			if("{".equals(start) && "}".equals(end) && "=".equals(fieldValueSeparator) && ", ".equals(fieldSeparator)){
				return Optional.ofNullable(doShort? inst.toShortString() : inst.toString());
			}
			
			var struct = inst.getThisStruct();
			return Optional.of(struct.instanceToString(inst, doShort, start, end, fieldValueSeparator, fieldSeparator));
		}
		
		if(val.getClass().isArray()){
			var res    = new StringJoiner(", ", "[", "]");
			int resLen = 0, len = Array.getLength(val), remaining = len;
			
			var comp      = val.getClass().componentType();
			var primitive = comp.isPrimitive();
			
			var oArr = primitive? null : (Object[])val;
			for(int i = 0; i<len; i++){
				var element = oArr != null? oArr[i] : Array.get(val, i);
				var elStr   = primitive? element + "" : Utils.toShortString(element);
				resLen += 2 + elStr.length();
				var lenNow = resLen + rem(remaining).length();
				if(doShort && lenNow>=200){
					Class<?> type;
					if(comp.isPrimitive()) type = comp;
					else{
						type = null;
						for(Object o : oArr){
							if(o == null) continue;
							var c = o.getClass();
							if(type == null) type = c;
							else type = UtilL.findClosestCommonSuper(type, c);
						}
					}
					return Optional.of(type.getSimpleName() + "[" + len + "]");
				}
				if(lenNow<(doShort? 100 : 200)){
					res.add(elStr);
					remaining--;
				}
			}
			if(remaining>0) res.add(rem(remaining));
			return Optional.of(res.toString());
		}
		if(val instanceof Collection<?> data){
			var res    = new StringJoiner(", ", "[", "]");
			int resLen = 0, remaining = data.size();
			for(var o : data){
				var e = Utils.toShortString(o);
				resLen += 2 + e.length();
				var lenNow = resLen + rem(remaining).length();
				if(doShort && lenNow>=200){
					var dataName = switch(data){
						case List<?> ignored -> "List";
						case Set<?> ignored -> "Set";
						default -> data.getClass().getSimpleName();
					};
					var type = UtilL.findClosestCommonSuper(
						data.stream()
						    .filter(Objects::nonNull)
						    .map(Object::getClass));
					if(type == Object.class) return Optional.of(dataName + "<?>[" + data.size() + "]");
					return Optional.of(dataName + "<" + type.getSimpleName() + ">[" + data.size() + "]");
				}
				if(lenNow<(doShort? 100 : 200)){
					res.add(e);
					remaining--;
				}
			}
			if(remaining>0) res.add(rem(remaining));
			return Optional.of(res.toString());
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
		if(accessor == null) return typeFlags;
		
		if(IOFieldTools.isGenerated(field)){
			typeFlags |= HAS_GENERATED_NAME;
		}
		
		var typeGen = accessor.getGenericType(null);
		var type    = accessor.getType();
		
		if(type == Optional.class){
			typeGen = IOFieldTools.unwrapOptionalTypeRequired(typeGen);
			type = Utils.typeToRaw(typeGen);
		}
		
		boolean isDynamic = IOFieldTools.isGeneric(accessor) || isSealedCached(type);
		if(isDynamic){
			typeFlags |= DYNAMIC_FLAG;
		}
		
		while(true){
			if(typeGen instanceof Class<?> c){
				if(c.isArray()){
					typeGen = c.componentType();
					continue;
				}
			}
			if(UtilL.instanceOf(Utils.typeToRaw(typeGen), List.class)){
				typeGen = switch(typeGen){
					case Class<?> c -> Object.class;
					case ParameterizedType t -> t.getActualTypeArguments()[0];
					default -> throw new NotImplementedException(typeGen.getClass() + "");
				};
				continue;
			}
			break;
		}
		
		var rawType = Utils.typeToRaw(typeGen);
		
		if(IOInstance.isInstance(rawType)){
			typeFlags |= IOINSTANCE_FLAG;
			
			if(!isDynamic && !(field instanceof RefField) && !Struct.canUnknownHavePointers(rawType)){
				typeFlags |= HAS_NO_POINTERS_FLAG;
			}
		}
		if(SupportedPrimitive.isAny(rawType) || rawType.isEnum()){
			typeFlags |= PRIMITIVE_OR_ENUM_FLAG;
		}
		
		if(rawType == Class.class){
			typeFlags |= HAS_NO_POINTERS_FLAG;
		}
		
		return typeFlags;
	}
}
