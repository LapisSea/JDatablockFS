package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.compilation.WrapperStructs;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractList;
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
	
	private static final class Arr extends AbstractList<Object>{
		private final Object arr;
		private final int    len;
		private Arr(Object arr, int len){
			this.arr = arr;
			this.len = len;
		}
		@Override
		public Object get(int index){ return Array.get(arr, index); }
		@Override
		public int size(){ return len; }
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	static <T extends IOInstance<T>> Optional<String> instanceToString(IOField<T, ?> field, VarPool<T> ioPool, T instance, StringifySettings settings){
		Object val;
		try{
			val = field.get(ioPool, instance);
		}catch(Throwable e){
			return Optional.of(IOFieldTools.corruptedGet(e));
		}
		if(val == null){
			if(field.isNonNullable()){
				return Optional.of(IOFieldTools.UNINITIALIZED_FIELD_SIGN);
			}
			return Optional.empty();
		}
		
		if(val instanceof IOInstance inst){
			if(settings.stringsEqual(StringifySettings.DEFAULT)){
				return Optional.ofNullable(settings.doShort()? inst.toShortString() : inst.toString());
			}
			
			var struct = inst.getThisStruct();
			return Optional.of(struct.instanceToString(inst, settings));
		}
		
		if(val.getClass().isArray()){
			val = new Arr(val, Array.getLength(val));
		}
		if(val instanceof Collection<?> data){
			int resLen = 0, remaining = data.size();
			if(remaining == 0) return Optional.empty();
			
			var res = new StringJoiner(", ", "[", "]");
			for(var o : data){
				var e = Utils.toShortString(o);
				resLen += 2 + e.length();
				var lenNow = resLen + rem(remaining).length();
				if(settings.doShort() && lenNow>=200){
					String prefix;
					if(data instanceof Arr arr){
						var typ = arr.arr.getClass().componentType();
						if(Modifier.isFinal(typ.getModifiers()) || SupportedPrimitive.getStrict(typ).isPresent()){
							prefix = typ.getTypeName();
						}else{
							var type = Utils.findClosestCommonSuper(Iters.from(arr).nonNulls().map(Object::getClass));
							prefix = (type == Object.class? typ : type).getTypeName();
						}
					}else{
						var type = Utils.findClosestCommonSuper(Iters.from(data).nonNulls().map(Object::getClass));
						var dataName = switch(data){
							case List<?> ignored -> "List";
							case Set<?> ignored -> "Set";
							default -> data.getClass().getSimpleName();
						};
						prefix = type == Object.class? dataName + "<?>" : dataName + "<" + type.getSimpleName() + ">";
					}
					return Optional.of(prefix + "[" + data.size() + "]");
				}
				if(lenNow<(settings.doShort()? 100 : 200)){
					res.add(e);
					remaining--;
				}
			}
			if(remaining>0) res.add(rem(remaining));
			return Optional.of(res.toString());
		}
		
		return Optional.of(
			settings.doShort()?
			Utils.toShortString(val) :
			TextUtil.toString(val)
		);
	}
	
	static int typeFlags(IOField<?, ?> field){
		int typeFlags = 0;
		var accessor  = field.getAccessor();
		if(accessor == null) return typeFlags;
		
		if(IOFieldTools.isGenerated(field)){
			typeFlags |= HAS_GENERATED_NAME_FLAG;
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
		
		if(IOInstance.isInstanceOrSealed(rawType)){
			typeFlags |= IO_INSTANCE_FLAG;
			
			if(!IOFieldTools.isGeneric(accessor) && !(field instanceof RefField) && !Struct.canUnknownHavePointers(rawType)){
				typeFlags |= HAS_NO_POINTERS_FLAG;
			}
		}
		if(SupportedPrimitive.isAny(rawType) || rawType.isEnum()){
			if(SupportedPrimitive.isAny(accessor.getType()) || accessor.getType().isEnum()){
				typeFlags |= PRIMITIVE_OR_ENUM_FLAG;
			}else{
				typeFlags |= HAS_NO_POINTERS_FLAG;
			}
		}
		
		if(UtilL.instanceOf(rawType, Type.class)){
			typeFlags |= HAS_NO_POINTERS_FLAG;
		}
		
		if(!(field instanceof RefField) && rawType == String.class || WrapperStructs.isWrapperType(rawType)){
			typeFlags |= HAS_NO_POINTERS_FLAG;
		}
		
		return typeFlags;
	}
}
