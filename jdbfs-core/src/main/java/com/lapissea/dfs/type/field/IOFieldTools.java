package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.InternalDataOrder;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.DepSort;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.compilation.Index;
import com.lapissea.dfs.type.compilation.TemplateClassLoader;
import com.lapissea.dfs.type.field.access.AnnotatedType;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.lapissea.dfs.Utils.None;
import static com.lapissea.dfs.Utils.Some;
import static com.lapissea.dfs.type.field.StoragePool.IO;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class IOFieldTools{
	
	public static <T extends IOInstance<T>> Function<List<IOField<T, ?>>, List<IOField<T, ?>>> streamStep(Function<IterablePP<IOField<T, ?>>, IterablePP<IOField<T, ?>>> map){
		return list -> map.apply(Iters.from(list)).toList();
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> stepFinal(List<IOField<T, ?>> data, Iterable<Function<List<IOField<T, ?>>, List<IOField<T, ?>>>> steps){
		List<IOField<T, ?>> d = data;
		for(Function<List<IOField<T, ?>>, List<IOField<T, ?>>> step : steps){
			d = step.apply(d);
		}
		return List.copyOf(d);
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> mergeBitSpace(List<IOField<T, ?>> mapData){
		var result     = new ArrayList<IOField<T, ?>>(mapData.size());
		var bitBuilder = new ArrayDeque<BitField<T, ?>>();
		
		Runnable pushBuilt = () -> {
			switch(bitBuilder.size()){
				case 0 -> { }
				case 1 -> result.add(bitBuilder.removeFirst());
				default -> {
					result.add(BitFieldMerger.of(List.copyOf(bitBuilder)));
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
	
	@SuppressWarnings("deprecation")
	public static <T extends IOInstance<T>> Index computeDependencyIndex(List<IOField<T, ?>> fields){
		switch(fields.size()){
			case 0 -> { return new Index(new int[]{}); }
			case 1 -> { return new Index(new int[]{0}); }
		}
		{
			var struct       = Iters.from(fields).map(IOField::getAccessor).firstNonNull().orElseThrow().getDeclaringStruct();
			var structType   = struct.getType();
			var dataOrderAnn = structType.getAnnotation(InternalDataOrder.class);
			if(dataOrderAnn != null) return predefinedOrder(fields, structType, dataOrderAnn);
		}
		try{
			return new DepSort<>(fields, f -> f.getDependencies()
			                                   .mappedToInt(o -> Iters.range(0, fields.size())
			                                                          .firstMatching(i -> fields.get(i).getAccessor() == o.getAccessor())
			                                                          .orElseThrow())
			).sort(Comparator.comparingInt((IOField<T, ?> f) -> {//Pull fixed fields back and enforce word space sort order
				                 var order = f.sizeDescriptorSafe().getWordSpace().sortOrder;
				                 if(!f.getSizeDescriptor().hasFixed()){
					                 order += 100000;
				                 }
				                 return order;
			                 })
			                 //Pull any temporary fields back to reduce unessecary field skipping when re-reading them
			                 .thenComparingInt(f -> f.isVirtual(StoragePool.IO)? 0 : 1)
			                 //pull any cheap to read/write fields back
			                 .thenComparingInt(f -> f.getType().isEnum() || SupportedPrimitive.isAny(f.getType())? 0 : 1)
			                 //Encourage fields with similar dependencies to be next to each other
			                 .thenComparing(f -> f.getDependencies().iter().joinAsStr(" / ", IOField::getName))
			                 //Eliminate JVM entropy. Make initial field order irrelevant
			                 .thenComparing(IOField::getName)
			);
		}catch(DepSort.CycleException e){
			throw new MalformedStruct("Field dependency cycle detected:\n" + TextUtil.toTable(e.cycle.mapData(fields)), e);
		}
	}
	@SuppressWarnings("deprecation")
	private static <T extends IOInstance<T>> Index predefinedOrder(List<IOField<T, ?>> fields, Class<T> structType, InternalDataOrder dataOrderAnn){
		if((!(structType.getClassLoader() instanceof TemplateClassLoader))){
			throw new MalformedStruct(
				"fmt", "{}#red is for internal use only. To be used only by {}#yellow", InternalDataOrder.class.getName(), TemplateClassLoader.class.getName());
		}
		var dataOrder    = dataOrderAnn.value();
		var actualNames  = Iters.from(fields).map(IOField::getName).toModSet();
		var dataOrderSet = Set.of(dataOrder);
		if(!dataOrderSet.equals(actualNames)){
			throw new MalformedStruct("fmt", "Data order and fields are not matching.\n{}#yellow,\n{}#red", actualNames, dataOrderSet);
		}
		
		var index = new int[dataOrder.length];
		
		for(int i = 0; i<dataOrder.length; i++){
			var name = dataOrder[i];
			index[i] = Iters.range(0, fields.size())
			                .firstMatching(idx -> fields.get(idx).getName().equals(name))
			                .orElseThrow();
		}
		
		var res = new Index(index);
		if(GlobalConfig.DEBUG_VALIDATION){
			var testNames = Iters.from(res.mapData(fields)).map(IOField::getName).toArray(String[]::new);
			if(!Arrays.equals(testNames, dataOrder)){
				throw new AssertionError("\n" +
				                         Arrays.toString(testNames) + "\n" +
				                         Arrays.toString(dataOrder));
			}
		}
		return res;
	}
	
	public static <T extends IOInstance<T>> Match<IOField<T, NumberSize>> getDynamicSize(FieldAccessor<T> field){
		String sizeName;
		if(field.getAnnotation(IODependency.NumSize.class) instanceof Some(var ann)){
			sizeName = ann.value();
		}else if(field.getAnnotation(IODependency.VirtualNumSize.class) instanceof Some(var ann)){
			sizeName = getNumSizeName(field, ann);
		}else if(field.getAnnotation(IODependency.class) instanceof Some(var ann) && Iters.from(ann.value()).anyEquals(FieldNames.numberSize(field))){
			//TODO: This is a bandage for template loaded classes, make annotation serialization more precise.
			sizeName = FieldNames.numberSize(field);
		}else{
			return Match.empty();
		}
		var opt = field.getDeclaringStruct().getFields().exact(NumberSize.class, sizeName);
		if(opt.isEmpty()) throw new ShouldNeverHappenError("Missing or invalid field should have been checked in annotation logic");
		return Match.of(opt.get());
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
	public static <T extends IOInstance<T>> long sumVars(List<? extends IOField<T, ?>> fields, ToLongFunction<SizeDescriptor<T>> mapper){
		long sum = 0L;
		for(int i = 0; i<fields.size(); i++){
			sum += mapper.applyAsLong(fields.get(i).getSizeDescriptor());
		}
		return sum;
	}
	
	public static <T extends IOInstance<T>> WordSpace minWordSpace(Collection<? extends IOField<T, ?>> fields){
		var acc = WordSpace.BYTE;
		for(IOField<T, ?> field : fields){
			var descriptor = field.getSizeDescriptor();
			var wordSpace  = descriptor.getWordSpace();
			acc = acc.min(wordSpace);
		}
		return acc;
	}
	
	public static boolean isNullable(GetAnnotation holder){
		return isNullable(new AnnotatedType(){
			@Override
			public Map<Class<? extends Annotation>, ? extends Annotation> getAnnotations(){
				var ann = holder.get(IONullability.class);
				return ann == null? Map.of() : Map.of(IONullability.class, ann);
			}
			@Override
			public Type getGenericType(GenericContext genericContext){ return null; }
		});
	}
	public static boolean isNullable(AnnotatedType holder){
		return getNullability(holder) == NULLABLE;
	}
	public static IONullability.Mode getNullability(AnnotatedType holder){
		return getNullability(holder, NOT_NULL);
	}
	public static IONullability.Mode getNullability(AnnotatedType holder, IONullability.Mode defaultMode){
		return getNullabilityOpt(holder).orElse(defaultMode);
	}
	public static Match<IONullability.Mode> getNullabilityOpt(AnnotatedType holder){
		return holder.getAnnotation(IONullability.class).map(IONullability::value);
	}
	
	public static boolean isGenerated(IOField<?, ?> field){
		return field.getName().indexOf(FieldNames.GENERATED_FIELD_SEPARATOR) != -1;
	}
	
	public static boolean isGeneric(AnnotatedType type){
		return type.hasAnnotation(IOValue.Generic.class);
	}
	public static boolean isGeneric(GetAnnotation type){
		return type.isPresent(IOValue.Generic.class);
	}
	
	public static <T extends IOInstance<T>> void requireFieldsEquals(T a, T b){
		requireFieldsEquals(a, b, "Instances required to be equal but");
	}
	
	public static <T extends IOInstance<T>> void requireFieldsEquals(T a, T b, String startMessage){
		requireFieldsEquals(a, b, new Collector<>(){
			@Override
			public Supplier<List<String[]>> supplier(){ return ArrayList::new; }
			@Override
			public BiConsumer<List<String[]>, String[]> accumulator(){ return List::add; }
			
			@Override
			public BinaryOperator<List<String[]>> combiner(){
				return (a, b) -> {
					var arr = new ArrayList<String[]>(a.size() + b.size());
					arr.addAll(a);
					arr.addAll(b);
					return arr;
				};
			}
			
			@Override
			public Function<List<String[]>, String> finisher(){
				return data -> {
					if(data.isEmpty()){
						data = new ArrayList<>();
						data.add(new String[]{"No reason??"});
					}
					
					int[] lengths = new int[Iters.from(data).mapToInt(s -> s.length).max(0)];
					for(var line : data){
						for(int i = 0; i<line.length; i++){
							lengths[i] = Math.max(lengths[i], line[i].length() + 1);
						}
					}
					
					StringBuilder sb = new StringBuilder();
					sb.append('\n').append(startMessage).append(':');
					for(var line : data){
						sb.append("\n\t");
						for(int i = 0; i<line.length; i++){
							if(i == 1) sb.append("not equal because ");
							sb.append(line[i]).append(" ".repeat(lengths[i] - line[i].length()));
						}
					}
					return sb.toString();
				};
			}
			
			@Override
			public Set<Characteristics> characteristics(){ return Set.of(); }
		});
	}
	
	/**
	 * String[] format:<br>
	 * {subject} not equal because {reason}[: optional context...]
	 */
	public static <T extends IOInstance<T>> void requireFieldsEquals(T a, T b, Collector<String[], List<String[]>, String> messageBuilder){
		if(a == null || b == null){
			if(a != b){
				var acum = messageBuilder.supplier().get();
				acum.add(new String[]{"Instances", "nullability is not matching"});
				throw new IllegalStateException(messageBuilder.finisher().apply(acum));
			}
			return;
		}
		
		var struct = a.getThisStruct();
		{
			var bs = b.getThisStruct();
			if(!bs.equals(struct)){
				var acum = messageBuilder.supplier().get();
				acum.add(new String[]{"Instances", "structs are not of the same type"});
				throw new IllegalStateException(messageBuilder.finisher().apply(acum));
			}
		}
		
		List<String[]> acum = null;
		
		for(IOField<T, ?> field : struct.getRealFields()){
			if(field.instancesEqual(null, a, null, b)){
				continue;
			}
			
			if(acum == null) acum = messageBuilder.supplier().get();
			var as = field.instanceToString(null, a, false).orElse("null");
			var bs = field.instanceToString(null, b, false).orElse("null");
			acum.add(new String[]{"Field " + field.getName(), "values are not equal:", as, bs});
		}
		
		if(acum == null) return;
		throw new IllegalStateException(messageBuilder.finisher().apply(acum));
	}
	
	public static Map<Class<? extends Annotation>, Annotation> computeAnnotations(Field field){
		var ann   = field.getAnnotations();
		var types = Arrays.stream(ann).map(Annotation::annotationType).collect(Collectors.toSet());
		return Iters.concat(
			Iters.from(ann),
			Iters.from(FieldCompiler.ANNOTATION_TYPES)
			     .filter(typ -> !types.contains(typ))
			     .<Annotation>map(at -> field.getDeclaringClass().getAnnotation(at))
			     .nonNulls()
		).toMap(Annotation::annotationType, Function.identity());
	}
	
	public static boolean isIOField(Method m){
		if(Modifier.isStatic(m.getModifiers())){
			return false;
		}
		return m.isAnnotationPresent(IOValue.class);
	}
	
	public static boolean isIOField(Field f){
		if(Modifier.isStatic(f.getModifiers())){
			return false;
		}
		if(f.isAnnotationPresent(IOValue.class)){
			return true;
		}
		return f.getDeclaringClass().isAnnotationPresent(IOValue.class);
	}
	
	public static String getNumSizeName(FieldAccessor<?> field, IODependency.VirtualNumSize size){
		var nam = size.name();
		if(nam.isEmpty()){
			return FieldNames.numberSize(field);
		}
		return nam;
	}
	public static boolean doesTypeHaveArgs(Type type){
		return switch(type){
			case Class<?> c -> false;
			case ParameterizedType t -> {
				for(var arg : t.getActualTypeArguments()){
					if(doesTypeHaveArgs(arg)) yield true;
				}
				yield false;
			}
			case TypeVariable<?> t -> true;
			case GenericArrayType t -> doesTypeHaveArgs(t.getGenericComponentType());
			case WildcardType t -> {
				for(var bound : t.getUpperBounds()){
					if(doesTypeHaveArgs(bound)) yield true;
				}
				for(var bound : t.getLowerBounds()){
					if(doesTypeHaveArgs(bound)) yield true;
				}
				yield false;
			}
			default -> throw new NotImplementedException(type.getClass().getName());
		};
	}
	
	public static Type unwrapOptionalTypeRequired(Type optionalType){
		return unwrapOptionalType(optionalType).orElseThrow(() -> {
			return new RuntimeException("Failed to unwrap optional type: " + optionalType.getTypeName());
		});
	}
	public static Optional<Type> unwrapOptionalType(Type optionalType){
		return switch(optionalType){
			case ParameterizedType typ -> Some(typ.getActualTypeArguments()[0]);
			default -> None();
		};
	}
	
	public static <T extends IOInstance<T>> List<IOField.ValueGeneratorInfo<T, ?>> fieldsToGenerators(SequencedCollection<? extends IOField<T, ?>> fields){
		
		int count = 0;
		for(var f : fields){
			count += f.getGenerators().size();
		}
		if(count == 0) return List.of();
		
		//noinspection unchecked
		IOField.ValueGeneratorInfo<T, ?>[] buff = new IOField.ValueGeneratorInfo[count];
		var                                pos  = 0;
		/*
		Reverse fields due to an assumption that they are sorted as to be a valid dependency order.
		If there are fields [fancy:value:isNull, fancy:value, fancy] ordered by dependency topology (fancy depends on fancy:value, so it is after it)
		Generators should be executed in reverse order, so it should be [{fancy -> fancy:value}, {fancy:value -> fancy:value:isNull}]
		If it is not reversed then isNull generator will be called first. At this point fancy:value is not generated and will always be null.
		*/
		for(var f : fields.reversed()){
			for(var g : f.getGenerators()){
				buff[pos++] = g;
			}
		}
		return List.of(buff);
	}
	
	public static final String UNINITIALIZED_FIELD_SIGN = "<Uninitialized>";
	
	public static String corruptedGet(Throwable e){
		var msg = e.getMessage();
		return "<CORRUPTED: " + (msg == null? Utils.typeToHuman(e.getClass()) : msg) + ">";
	}
	
	public static Optional<IOInstance.Order> tryGetOrImplyOrder(Struct<?> type){
		var clazz = type.getType();
		var ann   = clazz.getAnnotation(IOInstance.Order.class);
		if(ann != null) return Optional.of(ann);
		
		var fields = type.getFields().filtered(e -> !e.isVirtual(IO)).bake();
		if(fields.size()<=1){
			return Optional.of(orderFromNames(fields.map(IOField::getName)));
		}
		var types = fields.map(t -> t.getGenericType(null));
		if(types.hasDuplicates()){
			return Optional.empty();
		}
		var typeSet = types.toModSet();
		
		return Iters.from(clazz.getConstructors())
		            .filter(c -> Modifier.isPublic(c.getModifiers()))
		            .map(c -> Iters.from(c.getGenericParameterTypes()))
		            .filter(c -> c.count() == fields.size() && !c.hasDuplicates())
		            .firstMatching(c -> c.toModSet().equals(typeSet))
		            .map(typesOrder -> {
			            var typeLookup = fields.toModMap(f -> f.getGenericType(null), Function.identity());
			            var names      = typesOrder.map(typeLookup::get).map(IOField::getName);
			            return orderFromNames(names);
		            });
	}
	public static IOInstance.Order orderFromNames(IterablePP<String> names){
		return Annotations.make(IOInstance.Order.class, Map.of("value", names.toArray(String[]::new)));
	}
	
	public static <T extends IOInstance<T>> String toTableString(String title, Iterable<IOField<T, ?>> fields){
		return TextUtil.toTable(
			title,
			Iters.from(fields).map(f -> Iters.entries(
				"fieldType", typeName(f),
				"name", f.getName(),
				"type", f.getGenericType(null) == null? "/" : f.getGenericType(null).getTypeName(),
				"sizeDescriptor", f.getSizeDescriptor(),
				"nullability", f.getNullability(),
				"readOnly", f.isReadOnly(),
				"generators", f.getGenerators(),
				"dependencies", f.getDependencies()
			).filter(e -> {
				var v = e.getValue();
				if(v instanceof Collection<?> c){
					return !c.isEmpty();
				}
				return true;
			}).toModMap(new LinkedHashMap<>(), Map.Entry::getKey, Map.Entry::getValue)).toModList()
		);
	}
	private static <T extends IOInstance<T>> String typeName(IOField<T, ?> f){
		var typ = f.getClass();
		if(UtilL.instanceOf(typ, BitFieldMerger.class)) typ = BitFieldMerger.class;
		var name = typ.getTypeName();
		name = name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
		return name;
	}
}
