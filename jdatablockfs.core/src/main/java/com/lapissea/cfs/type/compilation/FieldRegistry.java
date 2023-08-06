package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOField.FieldUsage;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.util.LateInit;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;

final class FieldRegistry{
	
	private static final LateInit.Safe<List<FieldUsage>> USAGES = Runner.async(new Supplier<>(){
		@Override
		public List<FieldUsage> get(){
			Log.trace("{#yellowBrightDiscovering IOFields#}");
			
			var tasks = new ConcurrentLinkedDeque<LateInit.Safe<Optional<Map.Entry<Class<?>, List<FieldUsage>>>>>();
			scan(IOField.class, tasks);
			
			var scanned = new HashMap<Class<?>, List<FieldUsage>>();
			while(!tasks.isEmpty()){
				tasks.pop().get().ifPresent(
					e -> scanned.put(e.getKey(), e.getValue())
				);
			}
			var usages = scanned.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().getName()))
			                    .map(Map.Entry::getValue).flatMap(Collection::stream).toList();
			if(Log.TRACE) Log.trace("{#yellowBrightFound {} FieldUsage owners with {} usages#}", scanned.size(), usages.size());
			return usages;
		}
		
		private static void log(String str, Class<?> typ){
			if(!Log.TRACE) return;
			Thread.startVirtualThread(() -> Log.trace(str, typ));
		}
		
		private static void scan(Class<?> type, Deque<LateInit.Safe<Optional<Map.Entry<Class<?>, List<FieldUsage>>>>> tasks){
			if(type.getSimpleName().contains("NoIO")){
				log("Ignoring \"NoIO\" {#blackBright{}~#}", type);
				return;
			}
			if(type.isSealed()){
				var usage = getFieldUsage(type);
				if(usage.isPresent()){
					log("Sealed {#blackBright{}~#} has usage, ignoring children", type);
					tasks.add(new LateInit.Safe<>(() -> usage, Runnable::run));
					return;
				}
				log("Scanning sealed {#blackBright{}~#} children", type);
				var ch = new ConcurrentLinkedDeque<>(Arrays.asList(type.getPermittedSubclasses()));
				tasks.add(Runner.async(() -> {
					for(Class<?> c = ch.poll(); c != null; c = ch.poll()){
						scan(c, tasks);
					}
					return Optional.empty();
				}));
				for(Class<?> c = ch.poll(); c != null; c = ch.poll()){
					scan(c, tasks);
				}
				return;
			}
			if(Modifier.isAbstract(type.getModifiers())){
				return;
			}
			tasks.add(Runner.async(() -> {
				var typ0 = type;
				while(true){
					var typ = typ0;
					var res = getFieldUsage(typ);
					if(res.isPresent()){
						log("{#blackBright{}~#} has usage", typ);
						return res;
					}
					
					var up = typ.getEnclosingClass();
					if(up == null || up.isSealed()){
						log("{#blackBright{}~#} does NOT have usage", typ);
						return Optional.empty();
					}
					log("{#blackBright{}~#} does NOT have usage, scanning parent", typ);
					typ0 = up;
				}
			}));
		}
		
		private static Optional<Map.Entry<Class<?>, List<FieldUsage>>> getFieldUsage(Class<?> type){
			if(type == IOField.class) return Optional.empty();
			var usageClasses =
				Optional.ofNullable(type.getDeclaredAnnotation(IOField.FieldUsageRef.class))
				        .map(IOField.FieldUsageRef::value).filter(a -> a.length>0).map(List::of)
				        .orElseGet(() -> Arrays.stream(type.getDeclaredClasses())
				                               .filter(c -> UtilL.instanceOf(c, FieldUsage.class))
				                               .map(c -> {
					                               //noinspection unchecked
					                               return (Class<FieldUsage>)c;
				                               })
				                               .toList());
			
			if(usageClasses.isEmpty()){
				return Optional.empty();
			}
			
			return Optional.of(Map.entry(type, usageClasses.stream().map(u -> make(u)).toList()));
		}
		
		private static FieldUsage make(Class<FieldUsage> usageClass){
			Constructor<FieldUsage> constr;
			try{
				constr = usageClass.getDeclaredConstructor();
			}catch(NoSuchMethodException e){
				throw UtilL.exitWithErrorMsg(usageClass.getName() + " does not have an empty constructor");
			}
			try{
				constr.setAccessible(true);
				return constr.newInstance();
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("There was an issue instantiating " + usageClass.getName(), e);
			}
		}
	});
	
	private static Map<Class<?>, Set<FieldUsage>> PRODUCERS;
	private static List<Class<?>>                 WRAPPERS;
	
	
	private static List<FieldUsage> getData()                   { return USAGES.isInitialized() || !Log.TRACE? USAGES.get() : getDataLogged(); }
	private static Map<Class<?>, Set<FieldUsage>> getProducers(){ return PRODUCERS != null? PRODUCERS : (PRODUCERS = calcProducers()); }
	static List<Class<?>> getWrappers()                         { return WRAPPERS != null? WRAPPERS : (WRAPPERS = calcWrappers()); }
	
	private static Map<Class<?>, Set<FieldUsage>> calcProducers(){
		Map<Class<?>, Set<FieldUsage>> map = new HashMap<>();
		for(var f : getData()){
			for(var typ : f.listFieldTypes()){
				map.computeIfAbsent(typ, t -> new HashSet<>()).add(f);
			}
		}
		map.replaceAll((k, v) -> Set.copyOf(v));
		return Map.copyOf(map);
	}
	private static List<Class<?>> calcWrappers(){
		return Utils.getSealedUniverse(NullFlagCompanyField.class, true).orElseThrow()
		            .universe().stream()
		            .<Optional<Class<?>>>map((Class<NullFlagCompanyField> fieldType) -> {
			            var superC = (ParameterizedType)fieldType.getGenericSuperclass();
			            if(Utils.typeToRaw(superC) != NullFlagCompanyField.class){
				            return Optional.empty();
			            }
			            var args = superC.getActualTypeArguments();
			            if(!(args[1] instanceof Class<?> valueType)){
				            return Optional.empty();
			            }
			            return Optional.of(valueType);
		            })
		            .filter(Optional::isPresent).<Class<?>>map(Optional::get)
		            .sorted(Comparator.comparing(Class::getName)).toList();
	}
	private static List<FieldUsage> getDataLogged(){
		Log.trace("Waiting for FieldRegistry...");
		var start = System.nanoTime();
		var data  = USAGES.get();
		var end   = System.nanoTime();
		Log.trace("Waited {}ms for FieldRegistry", (end - start)/1000_000D);
		return data;
	}
	
	public static void init(){ }
	
	private FieldRegistry()  { }
	
	static boolean canCreate(Type type, GetAnnotation annotations){
		for(var usage : getData()){
			if(usage.isCompatible(type, annotations)){
				return true;
			}
		}
		return false;
	}
	static void requireCanCreate(Type type, GetAnnotation annotations){
		if(canCreate(type, annotations)){
			return;
		}
		throw fail(type.getTypeName());
	}
	
	static <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
		var ann  = GetAnnotation.from(field);
		var type = field.getGenericType(null);
		
		FieldUsage compatible;
		find:
		{
			for(var usage : getData()){
				if(usage.isCompatible(type, ann)){
					compatible = usage;
					break find;
				}
			}
			throw fail(type.getTypeName());
		}
		var res = compatible.create(field);
		if(DEBUG_VALIDATION){
			var typs = compatible.listFieldTypes();
			if(!typs.contains(res.getClass())){
				throw new RuntimeException(
					"Type " + res.getClass().getName() + "\nAllowed:\n" +
					typs.stream().map(Class::getName).collect(Collectors.joining("\n"))
				);
			}
		}
		return res;
	}
	
	private static final Set<Class<? extends Annotation>> CONSUMABLE_ANNOTATIONS
		= FieldCompiler.ANNOTATION_TYPES.stream().filter(t -> !List.of(IOValue.class, IODependency.class, IOValue.OverrideType.class).contains(t))
		                                .collect(Collectors.toUnmodifiableSet());
	
	private static <T extends IOInstance<T>> Set<FieldUsage> findUsages(IOField<T, ?> field){
		var             producers = getProducers();
		Set<FieldUsage> usages    = producers.get(field.getClass());
		if(usages == null) usages = producers.entrySet().stream()
		                                     .filter(e -> UtilL.instanceOf(field.getClass(), e.getKey()))
		                                     .findFirst().map(Map.Entry::getValue).orElse(null);
		return usages;
	}
	
	static <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(IOField<T, ?> field){
		var usages = findUsages(field);
		if(usages == null) return List.of();
		//noinspection unchecked
		var type = (Class<IOField<T, ?>>)field.getClass();
		var acc  = field.getAccessor();
		
		Set<VirtualFieldDefinition<T, ?>> result     = new HashSet<>();
		Set<Class<? extends Annotation>>  activeAnns = CONSUMABLE_ANNOTATIONS.stream().filter(acc::hasAnnotation).collect(Collectors.toSet());
		for(FieldUsage u : usages){
			for(var behaviour : u.annotationBehaviour(type)){
				behaviour.generateFields(acc).ifPresent(res -> {
					result.addAll(res.fields());
					activeAnns.remove(behaviour.annotationType());
					activeAnns.removeAll(res.touchedAnnotations());
				});
			}
		}
		
		if(!activeAnns.isEmpty()){
			throw new MalformedStruct(
				"Field " + field + " has incompatible " + TextUtil.plural("annotation", activeAnns.size()) + ":\n" +
				activeAnns.stream().map(t -> "\t" + t.getName()).collect(Collectors.joining("\n"))
			);
		}
		
		return result.stream().sorted(Comparator.comparing(VirtualFieldDefinition::name)).toList();
	}
	
	static <T extends IOInstance<T>> Set<String> getDependencyValueNames(IOField<T, ?> field){
		var usages = findUsages(field);
		if(usages == null) return Set.of();
		
		//noinspection unchecked
		var type = (Class<IOField<T, ?>>)field.getClass();
		var acc  = field.getAccessor();
		
		var deps = new HashSet<String>();
		acc.getAnnotation(IODependency.class).ifPresent(ann -> deps.addAll(Arrays.asList(ann.value())));
		
		for(FieldUsage u : usages){
			for(var behaviour : u.annotationBehaviour(type)){
				behaviour.getDependencyNames(acc).ifPresent(deps::addAll);
			}
		}
		return deps;
	}
	
	private static IllegalField fail(String typeName){
		throw new IllegalField("Unable to find implementation of " + IOField.class.getSimpleName() + " from " + typeName);
	}
}
