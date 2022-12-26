package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Index;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.compilation.DepSort;
import com.lapissea.cfs.type.field.access.AnnotatedType;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.BitField;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class IOFieldTools{
	
	public static final char GENERATED_FIELD_SEPARATOR = ':';
	
	public static <T extends IOInstance<T>> Function<List<IOField<T, ?>>, List<IOField<T, ?>>> streamStep(Function<Stream<IOField<T, ?>>, Stream<IOField<T, ?>>> map){
		return list -> map.apply(list.stream()).toList();
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> stepFinal(List<IOField<T, ?>> data, Iterable<Function<List<IOField<T, ?>>, List<IOField<T, ?>>>> steps){
		List<IOField<T, ?>> d = data;
		for(Function<List<IOField<T, ?>>, List<IOField<T, ?>>> step : steps){
			d = step.apply(d);
		}
		return List.copyOf(d);
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> mergeBitSpace(List<IOField<T, ?>> mapData){
		var result     = new LinkedList<IOField<T, ?>>();
		var bitBuilder = new LinkedList<BitField<T, ?>>();
		
		Runnable pushBuilt = () -> {
			switch(bitBuilder.size()){
				case 0 -> { }
				case 1 -> result.add(bitBuilder.remove(0));
				default -> {
					result.add(new BitFieldMerger<>(bitBuilder));
					bitBuilder.clear();
				}
			}
		};
		
		for(IOField<T, ?> field : mapData){
			if(field instanceof BitField<?, ?> bit){
				//noinspection unchecked
				bitBuilder.add((BitField<T, ?>)bit);
				continue;
			}
			pushBuilt.run();
			result.add(field);
		}
		pushBuilt.run();
		
		return result;
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> dependencyReorder(List<IOField<T, ?>> fields){
		return computeDependencyIndex(fields).mapData(fields);
	}
	
	public static <T extends IOInstance<T>> Index computeDependencyIndex(List<IOField<T, ?>> fields){
		try{
			return new DepSort<>(fields, f -> f.dependencyStream()
			                                   .mapToInt(o -> IntStream.range(0, fields.size())
			                                                           .filter(i -> fields.get(i).getAccessor() == o.getAccessor())
			                                                           .findAny()
			                                                           .orElseThrow())
			).sort(Comparator.comparingInt((IOField<T, ?> f) -> {//Pull fixed fields back and enforce word space sort order
				                 var order = f.sizeDescriptorSafe().getWordSpace().sortOrder;
				                 if(!f.getSizeDescriptor().hasFixed()){
					                 order += 100000;
				                 }
				                 return order;
			                 })
			                 //Pull any temporary fields back to reduce unessecary field skipping when re-reading them
			                 .thenComparingInt(f -> Utils.isVirtual(f, StoragePool.IO)? 0 : 1)
			                 //pull any cheap to read/write fields back
			                 .thenComparingInt(f -> f.getType().isEnum() || SupportedPrimitive.isAny(f.getType())? 0 : 1)
			                 //Encourage fields with similar dependencies to be next to each other
			                 .thenComparing(f -> f.dependencyStream().map(IOField::getName).collect(Collectors.joining(" / ")))
			                 //Eliminate JVM entropy. Make initial field order irrelevant
			                 .thenComparing(IOField::getName)
			);
		}catch(DepSort.CycleException e){
			throw new MalformedStruct("Field dependency cycle detected:\n" + TextUtil.toTable(e.cycle.mapData(fields)), e);
		}
	}
	
	public static <T extends IOInstance<T>> Optional<IOField<T, NumberSize>> getDynamicSize(FieldAccessor<T> field){
		Optional<String> dynSiz = Stream.of(
			field.getAnnotation(IODependency.NumSize.class).map(IODependency.NumSize::value),
			field.getAnnotation(IODependency.VirtualNumSize.class).map(e -> IODependency.VirtualNumSize.Logic.getName(field, e)),
			//TODO: This is a bandage for template loaded classes, make annotation serialization more precise.
			field.getAnnotation(IODependency.class).stream().flatMap(e -> Arrays.stream(e.value())).filter(name -> name.equals(makeNumberSizeName(field.getName()))).findAny()
		).filter(Optional::isPresent).map(Optional::get).findAny();
		
		if(dynSiz.isEmpty()) return Optional.empty();
		var opt = field.getDeclaringStruct().getFields().exact(NumberSize.class, dynSiz.get());
		if(opt.isEmpty()) throw new ShouldNeverHappenError("Missing or invalid field should have been checked in annotation logic");
		return opt;
	}
	
	public static <T extends IOInstance<T>> OptionalLong sumVarsIfAll(Collection<? extends IOField<T, ?>> fields, Function<SizeDescriptor<T>, OptionalLong> mapper){
		long sum = 0;
		for(IOField<T, ?> field : fields){
			var sizeDescriptor = field.getSizeDescriptor();
			var size           = mapper.apply(sizeDescriptor);
			if(size.isEmpty()) return size;
			sum += size.getAsLong();
		}
		return OptionalLong.of(sum);
	}
	public static <T extends IOInstance<T>> long sumVars(Collection<? extends IOField<T, ?>> fields, ToLongFunction<SizeDescriptor<T>> mapper){
		//return fields.stream().map(IOField::getSizeDescriptor).mapToLong(mapper).sum();
		long sum = 0L;
		for(IOField<T, ?> field : fields){
			sum += mapper.applyAsLong(field.getSizeDescriptor());
		}
		return sum;
	}
	
	public static <T extends IOInstance<T>> WordSpace minWordSpace(Collection<? extends IOField<T, ?>> fields){
		var acc = WordSpace.MIN;
		for(IOField<T, ?> field : fields){
			var descriptor = field.getSizeDescriptor();
			var wordSpace  = descriptor.getWordSpace();
			acc = acc.min(wordSpace);
		}
		return acc;
	}
	
	public static <T extends IOInstance<T>> String makeCollectionLenName(FieldAccessor<T> field){
		return field.getName() + GENERATED_FIELD_SEPARATOR + "len";
	}
	public static String makeNumberSizeName(String name){
		return name + GENERATED_FIELD_SEPARATOR + "nSiz";
	}
	public static <T extends IOInstance<T>> String makeGenericIDFieldName(FieldAccessor<T> field){
		return field.getName() + GENERATED_FIELD_SEPARATOR + "typeID";
	}
	public static <T extends IOInstance<T>> String makeNullFlagName(FieldAccessor<T> field){
		return field.getName() + GENERATED_FIELD_SEPARATOR + "isNull";
	}
	public static <T extends IOInstance<T>> String makeNullElementsFlagName(FieldAccessor<T> field){
		return field.getName() + GENERATED_FIELD_SEPARATOR + "areNull";
	}
	
	public static boolean isNullable(AnnotatedType holder){
		return getNullability(holder) == NULLABLE;
	}
	public static IONullability.Mode getNullability(AnnotatedType holder){
		return getNullability(holder, NOT_NULL);
	}
	public static IONullability.Mode getNullability(AnnotatedType holder, IONullability.Mode defaultMode){
		return holder.getAnnotation(IONullability.class).map(IONullability::value).orElse(defaultMode);
	}
	
	public static <T extends IOInstance<T>> String makeRefName(FieldAccessor<T> accessor){
		return makeRefName(accessor.getName());
	}
	public static String makeRefName(String baseName){
		return baseName + GENERATED_FIELD_SEPARATOR + "ref";
	}
	
	public static <T extends IOInstance<T>> String makePackName(FieldAccessor<T> accessor){
		return makePackName(accessor.getName());
	}
	public static String makePackName(String baseName){
		return baseName + GENERATED_FIELD_SEPARATOR + "pack";
	}
	
	public static IONullability makeNullabilityAnn(IONullability.Mode mode){
		return makeAnnotation(IONullability.class, Map.of("value", mode));
	}
	
	public static <E extends Annotation> E makeAnnotation(Class<E> annotationType){ return makeAnnotation(annotationType, Map.of()); }
	public static <E extends Annotation> E makeAnnotation(Class<E> annotationType, @NotNull Map<String, Object> values){
		Objects.requireNonNull(values);
		Class<?>[] interfaces = annotationType.getInterfaces();
		if(!annotationType.isAnnotation() || interfaces.length != 1 || interfaces[0] != Annotation.class){
			throw new IllegalArgumentException(annotationType.getName() + " not an annotation");
		}
		
		var safeValues = Arrays.stream(annotationType.getDeclaredMethods()).map(element -> {
			String elementName = element.getName();
			if(values.containsKey(elementName)){
				Class<?> returnType = element.getReturnType();
				
				if(returnType.isPrimitive()){
					if(returnType == boolean.class) returnType = Boolean.class;
					else if(returnType == char.class) returnType = Character.class;
					else if(returnType == float.class) returnType = Float.class;
					else if(returnType == double.class) returnType = Double.class;
					else if(returnType == byte.class) returnType = Byte.class;
					else if(returnType == short.class) returnType = Short.class;
					else if(returnType == int.class) returnType = Integer.class;
					else if(returnType == long.class) returnType = Long.class;
					else throw new ShouldNeverHappenError(returnType.toString());
				}
				
				if(returnType.isInstance(values.get(elementName))){
					return Map.entry(elementName, values.get(elementName));
				}else{
					throw new IllegalArgumentException("Incompatible type for " + elementName);
				}
			}else{
				if(element.getDefaultValue() != null){
					return Map.entry(elementName, element.getDefaultValue());
				}else{
					throw new IllegalArgumentException("Missing value " + elementName);
				}
			}
		}).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
		
		int hash = values.entrySet().stream().mapToInt(element -> {
			int    res;
			Object val = element.getValue();
			if(val.getClass().isArray()){
				res = 1;
				for(int i = 0; i<Array.getLength(val); i++){
					var el = Array.get(val, i);
					res = 31*res + Objects.hashCode(el);
				}
			}else res = Objects.hashCode(val);
			return (127*element.getKey().hashCode())^res;
		}).sum();
		
		class FakeAnnotation implements Annotation, InvocationHandler{
			
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable{
				if(safeValues.containsKey(method.getName())){
					return safeValues.get(method.getName());
				}
				return method.invoke(this, args);
			}
			
			@Override
			public Class<? extends Annotation> annotationType(){
				return annotationType;
			}
			
			@Override
			public boolean equals(Object other){
				if(this == other) return true;
				if(!annotationType.isInstance(other)) return false;
				
				var that    = annotationType.cast(other);
				var thatAnn = that.annotationType();
				
				return safeValues.entrySet().stream().allMatch(element -> {
					try{
						var thatVal = thatAnn.getMethod(element.getKey()).invoke(that);
						return Objects.deepEquals(element.getValue(), thatVal);
					}catch(ReflectiveOperationException e){
						throw new RuntimeException(e);
					}
				});
			}
			
			@Override
			public int hashCode(){
				return hash;
			}
			
			@Override
			public String toString(){
				return '@' + annotationType.getName() + TextUtil.toString(safeValues);
			}
		}
		
		//noinspection unchecked
		return (E)Proxy.newProxyInstance(annotationType.getClassLoader(),
		                                 new Class[]{annotationType},
		                                 new FakeAnnotation());
	}
	
	public static boolean isGenerated(IOField<?, ?> field){
		return field.getName().indexOf(GENERATED_FIELD_SEPARATOR) != -1;
	}
}
