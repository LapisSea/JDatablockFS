package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.IllegalAnnotation;
import com.lapissea.dfs.exceptions.IllegalField;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.internal.Preload;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.access.FunctionalReflectionAccessor;
import com.lapissea.dfs.type.field.access.ReflectionAccessor;
import com.lapissea.dfs.type.field.access.UnsafeAccessor;
import com.lapissea.dfs.type.field.access.VarHandleAccessor;
import com.lapissea.dfs.type.field.access.VirtualAccessor;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnmanagedValueInfo;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static java.util.Comparator.naturalOrder;
import static java.util.function.Function.identity;

public final class FieldCompiler{
	
	public enum AccessType{
		UNSAFE,
		VAR_HANDLE,
		REFLECTION
	}
	
	private static final AccessType FIELD_ACCESS = ConfigDefs.FIELD_ACCESS_TYPE.resolveLocking();
	
	/**
	 * Scans an unmanaged struct for
	 */
	public static <T extends IOInstance.Unmanaged<T>> FieldSet<T> compileStaticUnmanaged(Struct.Unmanaged<T> struct){
		var valueDefs = deepClasses(struct.getConcreteType())
			                .flatMapArray(Class::getDeclaredMethods)
			                .filter(m -> m.isAnnotationPresent(IOUnmanagedValueInfo.class))
			                .toModList();
		if(valueDefs.isEmpty()) return FieldSet.of();
		
		for(Method valueMethod : valueDefs){
			if(!Modifier.isStatic(valueMethod.getModifiers())){
				throw new IllegalField("fmt", "{}#red is not static!", valueMethod);
			}
			
			if(!UtilL.instanceOf(valueMethod.getReturnType(), IOUnmanagedValueInfo.Data.class)){
				throw new IllegalField("fmt", "{}#red does not return {}#yellow", valueMethod, IOUnmanagedValueInfo.Data.class.getName());
			}
			var returnTypeArg = switch(valueMethod.getGenericReturnType()){
				case ParameterizedType parm -> parm.getActualTypeArguments()[0];
				default -> throw new IllegalField(
					"fmt", "{}#red must be a {}#yellow", valueMethod, IOUnmanagedValueInfo.Data.class.getName() + "<This class>"
				);
			};
			var rawReturnType = Utils.typeToRaw(returnTypeArg);
			
			if(rawReturnType != valueMethod.getDeclaringClass()){
				throw new IllegalField(
					"fmt", """
					{}#red does not return type of same owner type!
					\tRaw return type: {}#red
					\tOwner type: {}#yellow""",
					valueMethod, rawReturnType.getName(), valueMethod.getDeclaringClass().getName()
				);
			}
		}
		
		return FieldSet.of(Iters.from(valueDefs).flatMap(valueDef -> {
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
		
		checkIOFieldValidity(accessors);
		
		var fields = new ArrayList<IOField<T, ?>>(Math.max(accessors.size()*2, accessors.size() + 5));//Give extra capacity for virtual fields
		for(var a : accessors){
			fields.add(FieldRegistry.create(a));
		}
		
		generateVirtualFields(fields, struct);
		
		initLateData(fields);
		
		return FieldSet.of(fields);
	}
	
	private static <T extends IOInstance<T>> void checkIOFieldValidity(List<FieldAccessor<T>> fields){
		var fails = Iters.from(fields)
		                 .filter(field -> !FieldRegistry.canCreate(field.getGenericType(null), GetAnnotation.from(field)))
		                 .asCollection();
		switch(fails.size()){
			case 0 -> { }
			case 1 -> throw new IllegalField("fmt", "Could not find implementation for: {}#red", fails.getFirst());
			default -> throw new IllegalField("fmt", "Could not find implementations for: \n{}#red", fails.joinAsStr("\n", e -> "\t" + e));
		}
	}
	
	private static <T extends IOInstance<T>> void validateClassAnnotations(Class<T> type){
		if(valName(type.getAnnotation(IOValue.class)).isPresent()){
			throw new IllegalAnnotation("fmt", "{}#red is not allowed to have a name when on a class", IOValue.class.getSimpleName());
		}
	}
	
	private static <T extends IOInstance<T>> FieldSet<T> generateDependencies(Map<String, IOField<T, ?>> fields, IOField<T, ?> field){
		var depNames = FieldRegistry.getDependencyValueNames(field);
		if(depNames.isEmpty()) return FieldSet.of();
		
		var dependencies = HashSet.<IOField<T, ?>>newHashSet(depNames.size());
		for(String nam : depNames){
			var dep = fields.get(nam);
			if(dep == null){
				throw new IllegalField("fmt", "Could not find dependencies {} on field {}#yellow",
				                       Iters.from(depNames)
				                            .filter(name -> !fields.containsKey(name))
				                            .joinAsStr(", ", f -> Log.fmt("{}#red", f)),
				                       field.getAccessor());
			}
			dependencies.add(dep);
		}
		return FieldSet.of(dependencies);
	}
	
	private static <T extends IOInstance<T>> void initLateData(List<IOField<T, ?>> fields){
		var mapFields = Iters.from(fields).toModMap(IOField::getName, identity());
		for(var field : fields){
			field.initLateData(generateDependencies(mapFields, field));
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
						existing = Iters.from(parsed).map(IOField::getAccessor)
						                .firstMatching(a -> a.getName().equals(s.name)).orElse(null);
					}
					if(existing != null){
						var gTyp = existing.getGenericType(null);
						if(!gTyp.equals(s.type)){
							throw new IllegalField(
								"fmt", "Virtual field {}#yellow already defined but has a type conflict of {}#red and {}#red",
								existing.getName(), gTyp, s.type
							);
						}
						continue;
					}
					
					VirtualAccessor.TypeOff typeOff;
					
					var primitive = SupportedPrimitive.get(s.type);
					
					if(primitive.isEmpty()){
						var index = accessIndex.compute(s.storagePool, (k, v) -> v == null? 0 : v + 1);
						typeOff = new VirtualAccessor.TypeOff.Ptr(index);
					}else{
						int size = (int)primitive.get().maxSize.get(WordSpace.BYTE);
						var off  = primitiveOffset.getOrDefault(s.storagePool, 0);
						typeOff = new VirtualAccessor.TypeOff.Primitive(off, size);
						primitiveOffset.put(s.storagePool, off + size);
					}
					
					//noinspection unchecked
					FieldAccessor<T> accessor = new VirtualAccessor<>(struct, (VirtualFieldDefinition<T, Object>)s, typeOff);
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
		return Iters.nullTerminated(() -> new Supplier<>(){
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
	
	private static final class GetSet{
		private Method getter;
		private Method setter;
		
		private IterablePP<Method> iter(){ return Iters.of(getter, setter); }
	}
	
	private static <T extends IOInstance<T>> List<FieldAccessor<T>> scanFields(Struct<T> struct){
		var cl = struct.getConcreteType();
		
		var usedFields = new HashSet<Method>();
		
		var ioMethods = allMethods(cl).filter(IOFieldTools::isIOField).asCollection();
		var fields    = collectAccessors(struct, ioMethods, usedFields::add);
		
		var hangingMethods = ioMethods.filter(method -> !usedFields.contains(method)).toModList();
		
		var functionFields = new HashMap<String, GetSet>();
		
		hangingMethods.removeIf(hangingMethod -> {
			var stub = CompilationTools.asStub(hangingMethod);
			stub.ifPresent(st -> {
				var p = functionFields.computeIfAbsent(st.varName(), n -> new GetSet());
				if(st.isGetter()) p.getter = hangingMethod;
				else p.setter = hangingMethod;
			});
			return stub.isPresent();
		});
		
		checkInvalidFunctionOnlyFields(functionFields, cl);
		
		checkForUnusedFunctions(functionFields, hangingMethods, fields);
		
		fields.sort(naturalOrder());
		
		var funFields = functionFieldsToAccessors(struct, functionFields);
		fields.addAll(funFields);
		fields.sort(naturalOrder());
		
		return fields;
	}
	
	private static <T extends IOInstance<T>> List<FieldAccessor<T>> functionFieldsToAccessors(Struct<T> struct, Map<String, GetSet> functionFields){
		List<FieldAccessor<T>> fields = new ArrayList<>(functionFields.size());
		for(var e : functionFields.entrySet()){
			String name = e.getKey();
			var    p    = e.getValue();
			
			Map<Class<? extends Annotation>, ? extends Annotation> annotations =
				p.iter().flatMapArray(Method::getAnnotations).distinct().toMap(Annotation::annotationType, identity());
			
			Method setter = p.setter, getter = p.getter;
			
			Type type = getType(getter.getGenericReturnType(), GetAnnotation.from(annotations));
			
			if(setter != null){
				Type setType = setter.getGenericParameterTypes()[0];
				if(!Utils.genericInstanceOf(type, setType)){
					throw new IllegalField(setType + " is not a valid argument in\n" + setter);
				}
			}
			
			fields.add(FunctionalReflectionAccessor.make(struct, name, getter, Optional.ofNullable(setter), annotations, type));
		}
		return fields;
	}
	
	private static <T extends IOInstance<T>> void checkForUnusedFunctions(
		Map<String, GetSet> functionFields, List<Method> hangingMethods, List<FieldAccessor<T>> fields
	){
		var gettersSetters = Iters.values(functionFields).flatMap(GetSet::iter);
		
		var unusedErr = Iters.from(hangingMethods).filter(gettersSetters::noneIs).map(method -> {
			String helpStr = "";
			if(Iters.from(fields).map(FieldAccessor::getName).anyEquals(method.getName())){
				helpStr = calcGetPrefixes(method).joinAsStr(" or ", " did you mean ", "?", p -> p + TextUtil.firstToUpperCase(method.getName()));
			}
			return method + helpStr;
		}).toModList();
		
		if(!unusedErr.isEmpty()){
			throw new MalformedStruct(
				"fmt", "There are unused or invalid methods marked with {}#yellow\n{}#red",
				IOValue.class.getSimpleName(),
				String.join("\n", unusedErr)
			);
		}
	}
	
	private static <T extends IOInstance<T>> List<FieldAccessor<T>> collectAccessors(
		Struct<T> struct, IterablePP<Method> ioMethods, Consumer<Method> reportField
	){
		var cl     = struct.getConcreteType();
		var fields = new ArrayList<FieldAccessor<T>>();
		
		for(Field field : deepClasses(cl).flatMapArray(Class::getDeclaredFields).filter(IOFieldTools::isIOField)){
			try{
				var getter = pickGSMethod(ioMethods, field, true);
				var setter = pickGSMethod(ioMethods, field, false);
				
				getter.ifPresent(reportField);
				setter.ifPresent(reportField);
				
				Type   type      = getType(field);
				String fieldName = getFieldName(field);
				field.setAccessible(true);
				
				fields.add(switch(FIELD_ACCESS){
					case UNSAFE -> UnsafeAccessor.make(struct, field, getter, setter, fieldName, type);
					case VAR_HANDLE -> VarHandleAccessor.make(struct, field, getter, setter, fieldName, type);
					case REFLECTION -> ReflectionAccessor.make(struct, field, getter, setter, fieldName, type);
				});
			}catch(Throwable e){
				throw new MalformedStruct("fmt", e, "Failed to scan field {#red #{}} on {}#yellow", field.getName(), struct.cleanName());
			}
		}
		return fields;
	}
	private static Optional<Method> pickGSMethod(IterablePP<Method> ioMethods, Field field, boolean getter){
		return ioMethods.firstMatching(m -> {
			if(!IOFieldTools.isIOField(m)) return false;
			var name = CompilationTools.asStub(m).filter(s -> s.isGetter() == getter).map(CompilationTools.FieldStub::varName);
			return name.filter(n -> n.equals(getFieldName(field))).isPresent();
		});
	}
	
	private static <T extends IOInstance<T>> void checkInvalidFunctionOnlyFields(Map<String, GetSet> functionFields, Class<T> cl){
		Iters.entries(functionFields).filter(e -> e.getValue().getter == null)
		     .joinAsOptionalStr("\n", e -> "\t" + e.getKey() + ": getter is missing!")
		     .ifPresent(invalidFields -> {
			     throw new IllegalField("fmt", "Invalid transient (getter+setter, no field) IOField(s) for {}#yellow:\n{}#red",
			                            cl.getName(), invalidFields);
		     });
	}
	
	private static IterablePP<String> calcGetPrefixes(Method method){ return calcGetPrefixes(method.getReturnType()); }
	private static IterablePP<String> calcGetPrefixes(Class<?> typ){
		if(typ == boolean.class || typ == Boolean.class) return Iters.of("is", "get");
		return Iters.of("get");
	}
	
	private static String getFieldName(Field field){
		return valName(field.getAnnotation(IOValue.class)).orElse(field.getName());
	}
	
	private static Optional<String> valName(IOValue ann){
		return Optional.ofNullable(ann).map(IOValue::name).filter(s -> !s.isEmpty());
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
	
	private static IterablePP<Method> allMethods(Class<?> clazz){
		return Iters.iterate(clazz, Objects::nonNull, (UnaryOperator<Class<?>>)Class::getSuperclass).flatMapArray(Class::getDeclaredMethods);
	}
	
	@SuppressWarnings("unchecked")
	public static final List<Class<? extends Annotation>> ANNOTATION_TYPES =
		Iters.from(activeAnnotations())
		     .flatMap(ann -> Iters.concat1N(
			     ann, Iters.of(ann.getClasses())
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
			IOUnsafeValue.class
		);
	}
	
	static{
		Preload.preload(FieldRegistry.class, MethodHandles.lookup());
		Preload.preloadFn(Annotations.class, "make", IOValue.class);
	}
	
	public static Collection<Class<?>> getWrapperTypes(){
		return FieldRegistry.getWrappers();
	}
}
