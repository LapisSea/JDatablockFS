package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.config.ConfigDefs;
import com.lapissea.cfs.exceptions.IllegalAnnotation;
import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.StoragePool;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.access.FunctionalReflectionAccessor;
import com.lapissea.cfs.type.field.access.ReflectionAccessor;
import com.lapissea.cfs.type.field.access.UnsafeAccessor;
import com.lapissea.cfs.type.field.access.VarHandleAccessor;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOIndexed;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOUnmanagedValueInfo;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.IterablePP;
import com.lapissea.cfs.utils.IterablePPs;
import com.lapissea.util.PairM;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import ru.vyarus.java.generics.resolver.GenericsResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

public final class FieldCompiler{
	
	public enum AccessType{
		UNSAFE,
		VAR_HANDLE,
		REFLECTION
	}
	
	private static final AccessType FIELD_ACCESS = ConfigDefs.FIELD_ACCESS_TYPE.resolve();
	
	/**
	 * Scans an unmanaged struct for
	 */
	public static <T extends IOInstance.Unmanaged<T>> FieldSet<T> compileStaticUnmanaged(Struct.Unmanaged<T> struct){
		var valueDefs = deepClasses(struct.getConcreteType())
			                .flatArray(Class::getDeclaredMethods)
			                .stream()
			                .filter(m -> m.isAnnotationPresent(IOUnmanagedValueInfo.class))
			                .toList();
		if(valueDefs.isEmpty()) return FieldSet.of();
		
		for(Method valueMethod : valueDefs){
			if(!Modifier.isStatic(valueMethod.getModifiers())){
				throw new IllegalField(valueMethod + " is not static!");
			}
			
			var context = GenericsResolver.resolve(valueMethod.getDeclaringClass()).method(valueMethod);
			
			if(!UtilL.instanceOf(context.resolveReturnClass(), IOUnmanagedValueInfo.Data.class)){
				throw new IllegalField(valueMethod + " does not return " + IOField.class.getName());
			}
			
			Class<?> ioFieldOwner = context.returnType().type(IOUnmanagedValueInfo.Data.class).generic("T");
			
			if(ioFieldOwner != valueMethod.getDeclaringClass()){
				throw new IllegalField(valueMethod + " does not return IOField of same owner type!\n" + ioFieldOwner.getName() + "\n" + valueMethod.getDeclaringClass().getName());
			}
		}
		
		return FieldSet.of(valueDefs.stream().flatMap(valueDef -> {
			valueDef.setAccessible(true);
			
			try{
				//noinspection unchecked
				var data = (IOUnmanagedValueInfo.Data<T>)valueDef.invoke(null);
				return data.getFields();
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}));
	}
	
	/**
	 * Detects and generates fields from {@link Struct} type class data and all its super types.
	 *
	 * @return a {@link FieldSet} containing all {@link IOValue} fields
	 */
	public static <T extends IOInstance<T>> FieldSet<T> compile(Struct<T> struct){
		validateClassAnnotations(struct.getType());
		
		var accessors = scanFields(struct);
		
		var fields = new ArrayList<IOField<T, ?>>(Math.max(accessors.size()*2, accessors.size() + 5));//Give extra capacity for virtual fields
		for(var a : accessors){
			fields.add(FieldRegistry.create(a));
		}
		
		generateVirtualFields(fields, struct);
		
		initLateData(fields);
		
		return FieldSet.of(fields);
	}
	
	private static <T extends IOInstance<T>> void validateClassAnnotations(Class<T> type){
		var cVal = type.getAnnotation(IOValue.class);
		if(cVal != null && !cVal.name().isEmpty()){
			throw new IllegalAnnotation(IOValue.class.getSimpleName() + " is not allowed to have a name when on a class");
		}
	}
	
	private static <T extends IOInstance<T>> FieldSet<T> generateDependencies(Map<String, IOField<T, ?>> fields, IOField<T, ?> field){
		var depNames = FieldRegistry.getDependencyValueNames(field);
		if(depNames.isEmpty()) return FieldSet.of();
		
		var dependencies = HashSet.<IOField<T, ?>>newHashSet(depNames.size());
		for(String nam : depNames){
			var dep = fields.get(nam);
			if(dep == null){
				throw new IllegalField("Could not find dependencies " +
				                       depNames.stream()
				                               .filter(name -> !fields.containsKey(name))
				                               .collect(joining(", ")) +
				                       " on field " + field.getAccessor());
			}
			dependencies.add(dep);
		}
		return FieldSet.of(dependencies);
	}
	
	private static <T extends IOInstance<T>> void initLateData(List<IOField<T, ?>> fields){
		var mapFields = fields.stream().collect(Collectors.toMap(IOField::getName, identity()));
		for(int i = 0; i<fields.size(); i++){
			var field = fields.get(i);
			field.initLateData(i, generateDependencies(mapFields, field));
		}
	}
	
	private static <T extends IOInstance<T>> void generateVirtualFields(List<IOField<T, ?>> parsed, Struct<T> struct){
		
		var accessIndex     = new EnumMap<StoragePool, Integer>(StoragePool.class);
		var primitiveOffset = new EnumMap<StoragePool, Integer>(StoragePool.class);
		var virtualData     = new HashMap<String, FieldAccessor<T>>();
		var newVirtualData  = new HashMap<String, FieldAccessor<T>>();
		
		List<IOField<T, ?>> toRun = new ArrayList<>(parsed);
		
		do{
			for(IOField<T, ?> field : toRun){
				var toInject = FieldRegistry.injectPerInstanceValue(field);
				
				for(var s : toInject){
					var existing = virtualData.get(s.name);
					if(existing == null){
						existing = parsed.stream().map(IOField::getAccessor)
						                 .filter(a -> a.getName().equals(s.name))
						                 .findAny().orElse(null);
					}
					if(existing != null){
						var gTyp = existing.getGenericType(null);
						if(!gTyp.equals(s.type)){
							throw new IllegalField("Virtual field " + existing.getName() + " already defined but has a type conflict of " + gTyp + " and " + s.type);
						}
						continue;
					}
					
					int primitiveSize, off, ptrIndex;
					
					if(!(s.type instanceof Class<?> c) || !c.isPrimitive()){
						primitiveSize = off = -1;
						ptrIndex = accessIndex.compute(s.storagePool, (k, v) -> v == null? 0 : v + 1);
					}else{
						if(List.of(long.class, double.class).contains(s.type)){
							primitiveSize = 8;
						}else if(List.of(byte.class, boolean.class).contains(s.type)){
							primitiveSize = 1;
						}else{
							primitiveSize = 4;
						}
						off = primitiveOffset.getOrDefault(s.storagePool, 0);
						int offEnd = off + primitiveSize;
						primitiveOffset.put(s.storagePool, offEnd);
						
						ptrIndex = -1;
					}
					
					//noinspection unchecked
					FieldAccessor<T> accessor = new VirtualAccessor<>(struct, (VirtualFieldDefinition<T, Object>)s, ptrIndex, off, primitiveSize);
					virtualData.put(s.name, accessor);
					newVirtualData.put(s.name, accessor);
				}
			}
			toRun.clear();
			for(var virtual : newVirtualData.values()){
				var field = FieldRegistry.create(virtual);
				toRun.add(field);
				parsed.add(field);
			}
			parsed.sort(Comparator.comparing(IOField::getAccessor));
			newVirtualData.clear();
		}while(!toRun.isEmpty());
	}
	
	
	private static IterablePP<Class<?>> deepClasses(Class<?> clazz){
		return IterablePPs.nullTerminated(() -> new Supplier<>(){
			Class<?> c = clazz;
			@Override
			public Class<?> get(){
				if(c == null) return null;
				var tmp = c;
				var cp  = c.getSuperclass();
				if(cp != null && (cp == IOInstance.class || !IOInstance.isInstance(cp))){
					cp = null;
				}
				c = cp == c? null : cp;
				
				return tmp;
			}
		});
	}
	
	private static <T extends IOInstance<T>> List<FieldAccessor<T>> scanFields(Struct<T> struct){
		var cl = struct.getConcreteType();
		
		var fields     = new ArrayList<FieldAccessor<T>>();
		var usedFields = new HashSet<Method>();
		
		var ioMethods = allMethods(cl).filter(IOFieldTools::isIOField).toList();
		
		for(Field field : deepClasses(cl).flatArray(Class::getDeclaredFields).filtered(IOFieldTools::isIOField)){
			try{
				Type type = getType(field);
				
				field.setAccessible(true);
				
				String fieldName = getFieldName(field);
				
				Optional<Method> getter, setter;
				if(UtilL.instanceOf(cl, IOInstance.Def.class)){
					IntFunction<Optional<Method>> getMethod = count -> ioMethods.stream().filter(
						m -> m.getParameterCount() == count &&
						     m.getAnnotation(IOValue.class).name().equals(fieldName)
					).findAny();
					getter = getMethod.apply(0);
					setter = getMethod.apply(1);
				}else{
					Function<String, Optional<Method>> getMethod =
						prefix -> ioMethods.stream().filter(m -> checkMethod(fieldName, prefix, m)).findFirst();
					getter = calcGetPrefixes(field).map(getMethod).filter(Optional::isPresent).map(Optional::get).findAny();
					setter = getMethod.apply("set");
				}
				
				getter.ifPresent(usedFields::add);
				setter.ifPresent(usedFields::add);
				
				fields.add(switch(FIELD_ACCESS){
					case UNSAFE -> UnsafeAccessor.make(struct, field, getter, setter, fieldName, type);
					case VAR_HANDLE -> VarHandleAccessor.make(struct, field, getter, setter, fieldName, type);
					case REFLECTION -> ReflectionAccessor.make(struct, field, getter, setter, fieldName, type);
				});
			}catch(Throwable e){
				throw new MalformedStruct("Failed to scan field #" + field.getName() + " on " + struct.cleanName(), e);
			}
		}
		
		var hangingMethods = ioMethods.stream().filter(method -> !usedFields.contains(method)).collect(Collectors.toList());
		
		Map<String, PairM<Method, Method>> functionFields = new HashMap<>();
		BiConsumer<String, Method>         pushGetter     = (name, m) -> functionFields.computeIfAbsent(name, n -> new PairM<>()).obj1 = m;
		BiConsumer<String, Method>         pushSetter     = (name, m) -> functionFields.computeIfAbsent(name, n -> new PairM<>()).obj2 = m;
		
		for(Method hangingMethod : hangingMethods){
			calcGetPrefixes(hangingMethod).map(p -> getMethodFieldName(p, hangingMethod))
			                              .filter(Optional::isPresent).map(Optional::get)
			                              .findFirst().ifPresent(s -> pushGetter.accept(s, hangingMethod));
			getMethodFieldName("set", hangingMethod).ifPresent(s -> pushSetter.accept(s, hangingMethod));
		}
		
		hangingMethods.removeIf(hangingMethod -> {
			var f = hangingMethod.getAnnotation(IOValue.class);
			if(f == null || f.name().isEmpty()) return false;
			
			if(CompilationTools.asGetterStub(hangingMethod).isPresent()){
				pushGetter.accept(f.name(), hangingMethod);
				return true;
			}
			if(CompilationTools.asSetterStub(hangingMethod).isPresent()){
				pushSetter.accept(f.name(), hangingMethod);
				return true;
			}
			
			return false;
		});
		{
			var errors = functionFields.entrySet()
			                           .stream()
			                           .filter(e -> e.getValue().stream().anyMatch(Objects::isNull))
			                           .toList();
			if(!errors.isEmpty()){
				throw new IllegalField(
					"Invalid transient (getter+setter, no value) " + TextUtil.plural("IOField", errors.size()) +
					" for " + cl.getName() + ":\n" +
					errors.stream()
					      .map(e -> "\t" + e.getKey() + ": " + (e.getValue().obj1 == null? "getter" : "setter") + " missing")
					      .collect(joining("\n"))
				);
			}
		}
		{
			Predicate<Method> isGetterOrSetter =
				m -> functionFields.values().stream()
				                   .flatMap(PairM::stream)
				                   .anyMatch(mt -> mt == m);
			var unusedWaning =
				hangingMethods.stream()
				              .filter(not(isGetterOrSetter))
				              .map(method -> {
					              String helpStr = "";
					              if(fields.stream().anyMatch(f -> f.getName().equals(method.getName()))){
						              helpStr = " did you mean " + calcGetPrefixes(method).map(p -> p + TextUtil.firstToUpperCase(method.getName()))
						                                                                  .collect(joining(" or ")) + "?";
					              }
					              return method + helpStr;
				              })
				              .collect(joining("\n"));
			if(!unusedWaning.isEmpty()){
				throw new MalformedStruct("There are unused or invalid methods marked with " + IOValue.class.getSimpleName() + "\n" + unusedWaning);
			}
		}
		
		fields.sort(Comparator.naturalOrder());
		
		for(var e : functionFields.entrySet()){
			String name = e.getKey();
			var    p    = e.getValue();
			
			Method getter = p.obj1, setter = p.obj2;
			
			Map<Class<? extends Annotation>, ? extends Annotation> annotations =
				Stream.of(getter.getAnnotations(), setter.getAnnotations())
				      .flatMap(Arrays::stream)
				      .distinct()
				      .collect(Collectors.toUnmodifiableMap(Annotation::annotationType, identity()));
			Type type = getType(getter.getGenericReturnType(), GetAnnotation.from(annotations));
			
			Type setType = setter.getGenericParameterTypes()[0];
			if(!Utils.genericInstanceOf(type, setType)){
				throw new IllegalField(setType + " is not a valid argument in\n" + setter);
			}
			
			UtilL.addRemainSorted(fields, FunctionalReflectionAccessor.make(struct, name, getter, setter, annotations, type));
		}
		
		{
			var fails = fields.stream()
			                  .filter(field -> {
				                  return !FieldRegistry.canCreate(field.getGenericType(null), GetAnnotation.from(field));
			                  })
			                  .toList();
			if(!fails.isEmpty()){
				throw new IllegalField(
					"Could not find " + TextUtil.plural("implementation", fails.size()) + " for: " +
					(fails.size()>1? "\n" : "") + fails.stream().map(Object::toString).collect(joining("\n"))
				);
			}
		}
		
		return fields;
	}
	
	private static Stream<String> calcGetPrefixes(Field field)  { return calcGetPrefixes(field.getType()); }
	private static Stream<String> calcGetPrefixes(Method method){ return calcGetPrefixes(method.getReturnType()); }
	private static Stream<String> calcGetPrefixes(Class<?> typ){
		var isBool = typ == boolean.class || typ == Boolean.class;
		if(isBool) return Stream.of("is", "get");
		return Stream.of("get");
	}
	
	private static Optional<String> getMethodFieldName(String prefix, Method m){
		IOValue ann   = m.getAnnotation(IOValue.class);
		var     mName = m.getName();
		if(!mName.startsWith(prefix)) return Optional.empty();
		
		if(ann.name().isEmpty()){
			if(mName.length()<=prefix.length() || Character.isLowerCase(mName.charAt(prefix.length()))) return Optional.empty();
			StringBuilder name = new StringBuilder(mName.length() - prefix.length());
			name.append(mName, prefix.length(), mName.length());
			name.setCharAt(0, Character.toLowerCase(name.charAt(0)));
			return Optional.of(name.toString());
		}else{
			return Optional.of(ann.name());
		}
	}
	private static boolean checkMethod(String fieldName, String prefix, Method m){
		if(!IOFieldTools.isIOField(m)) return false;
		var name = getMethodFieldName(prefix, m);
		return name.isPresent() && name.get().equals(fieldName);
	}
	private static String getFieldName(Field field){
		String fieldName;
		{
			var ann = field.getAnnotation(IOValue.class);
			fieldName = ann == null || ann.name().isEmpty()? field.getName() : ann.name();
		}
		return fieldName;
	}
	
	public static Type getType(Field field){
		return getType(field.getGenericType(), field::getAnnotation);
	}
	
	public static Type getType(Type defaultType, GetAnnotation getAnnotation){
		Type type         = defaultType;
		var  typeOverride = getAnnotation.get(IOValue.OverrideType.class);
		if(typeOverride != null){
			var      rawType = SyntheticParameterizedType.generalize(type);
			Class<?> raw     = rawType.getRawType();
			var      parms   = rawType.getActualTypeArgumentsList();
			
			if(typeOverride.value() != Object.class) raw = typeOverride.value();
			if(typeOverride.genericArgs().length != 0) parms = List.of(typeOverride.genericArgs());
			
			type = SyntheticParameterizedType.of(raw, parms);
		}
		var rt = Utils.typeToRaw(type);
		if(rt.isInterface()){
			var impl = rt.getAnnotation(IOValue.OverrideType.DefaultImpl.class);
			if(impl != null){
				var      rawType = SyntheticParameterizedType.generalize(type);
				Class<?> raw     = impl.value();
				if(!IOInstance.isInstance(raw)) throw new IllegalStateException();
				var parms = rawType.getActualTypeArgumentsList();
				type = SyntheticParameterizedType.of(raw, parms);
			}
		}
		
		return type;
	}
	
	private static Stream<Method> allMethods(Class<?> clazz){
		return Stream.iterate(clazz, Objects::nonNull, (UnaryOperator<Class<?>>)Class::getSuperclass)
		             .flatMap(c -> Arrays.stream(c.getDeclaredMethods()));
	}
	
	@SuppressWarnings("unchecked")
	public static final List<Class<? extends Annotation>> ANNOTATION_TYPES =
		activeAnnotations()
			.stream()
			.flatMap(ann -> Stream.concat(
				Stream.of(ann),
				Arrays.stream(ann.getClasses())
				      .filter(Class::isAnnotation)
				      .map(c -> (Class<? extends Annotation>)c)
			))
			.toList();
	
	private static Set<Class<? extends Annotation>> activeAnnotations(){
		return Set.of(
			IOValue.class,
			IODependency.class,
			IONullability.class,
			IOCompression.class,
			IOIndexed.class
		);
	}
	
	public static void init(){ }
	static{
		Thread.startVirtualThread(FieldRegistry::init);
	}
	
	public static Collection<Class<?>> getWrapperTypes(){
		return FieldRegistry.getWrappers();
	}
}
