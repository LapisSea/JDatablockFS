package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigUtils;
import com.lapissea.dfs.exceptions.IllegalAnnotation;
import com.lapissea.dfs.exceptions.IllegalField;
import com.lapissea.dfs.internal.Runner;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOField.FieldUsage;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.AnnotationUsage;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldWrapper;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

import static com.lapissea.dfs.SealedUtil.getPermittedSubclasses;
import static com.lapissea.dfs.SealedUtil.getSealedUniverse;
import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

final class FieldRegistry{
	
	private static final CompletableFuture<List<FieldUsage>> USAGES = Runner.async(new Supplier<>(){
		@Override
		public List<FieldUsage> get(){
			Log.trace("{#yellowBrightDiscovering IOFields#}");
			
			var log = Log.TRACE && !ConfigUtils.configBoolean(FieldRegistry.class.getName() + "#printed", false);
			if(log) System.setProperty(FieldRegistry.class.getName() + "#printed", "true");
			
			var tasks = new ConcurrentLinkedDeque<CompletableFuture<Optional<Map.Entry<Class<?>, List<FieldUsage>>>>>();
			var lines = log? new ConcurrentLinkedDeque<String>() : null;
			scan(IOField.class, tasks, lines);
			
			var scanned = new HashMap<Class<?>, List<FieldUsage>>();
			while(!tasks.isEmpty()){
				var any = tasks.removeIf(c -> {
					if(!c.isDone()) return false;
					c.join().ifPresent(
						e -> scanned.put(e.getKey(), e.getValue())
					);
					return true;
				});
				if(lines != null && !lines.isEmpty()){
					var res = new StringJoiner("\n");
					while(!lines.isEmpty()) res.add(lines.pop());
					Log.log(res.toString());
				}
				if(!any) UtilL.sleep(0.1);
			}
			
			var usages = Iters.entries(scanned).sortedBy(e -> e.getKey().getName())
			                  .flatMap(Map.Entry::getValue).toList();
			
			if(log) Log.trace("{#yellowBrightFound {} FieldUsage owners with {} usages#}", scanned.size(), usages.size());
			return usages;
		}
		
		private static void log(String str, Class<?> typ, Deque<String> lines){
			if(lines != null) lines.add(Log.fmt(str, typ));
		}
		
		private static void scan(Class<?> type, Deque<CompletableFuture<Optional<Map.Entry<Class<?>, List<FieldUsage>>>>> tasks, Deque<String> lines){
			if(type.getSimpleName().contains("NoIO")){
				log("Ignoring \"NoIO\" {#blackBright{}~#}", type, lines);
				return;
			}
			if(isSealedCached(type)){
				var usage = getFieldUsage(type);
				if(usage.isPresent()){
					log("Sealed {#blackBright{}~#} has usage, ignoring children", type, lines);
					tasks.add(CompletableFuture.completedFuture(usage));
					return;
				}
				log("Scanning sealed {#blackBright{}~#} children", type, lines);
				var ch = new ConcurrentLinkedDeque<>(getPermittedSubclasses(type));
				tasks.add(Runner.async(() -> {
					for(Class<?> c = ch.poll(); c != null; c = ch.poll()){
						scan(c, tasks, lines);
					}
					return Optional.empty();
				}));
				for(Class<?> c = ch.poll(); c != null; c = ch.poll()){
					scan(c, tasks, lines);
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
						log("{#blackBright{}~#} has usage", typ, lines);
						return res;
					}
					
					var up = typ.getEnclosingClass();
					if(up == null || isSealedCached(up)){
						log("{#blackBright{}~#} does NOT have usage", typ, lines);
						return Optional.empty();
					}
					log("{#blackBright{}~#} does NOT have usage, scanning parent", typ, lines);
					typ0 = up;
				}
			}));
		}
		
		private static Optional<Map.Entry<Class<?>, List<FieldUsage>>> getFieldUsage(Class<?> type){
			if(type == IOField.class) return Optional.empty();
			var usageClasses =
				Optional.ofNullable(type.getDeclaredAnnotation(IOField.FieldUsageRef.class))
				        .map(IOField.FieldUsageRef::value).filter(a -> a.length>0).map(List::of)
				        .orElseGet(() -> Iters.from(type.getDeclaredClasses())
				                              .filter(c -> UtilL.instanceOf(c, FieldUsage.class))
				                              .toList(c -> {
					                              //noinspection unchecked
					                              return (Class<FieldUsage>)c;
				                              }));
			
			if(usageClasses.isEmpty()){
				return Optional.empty();
			}
			
			return Optional.of(Map.entry(type, Iters.from(usageClasses).toList(u -> make(u))));
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
	
	private static List<FieldUsage> getData(){ return USAGES.isDone() || !Log.TRACE? USAGES.join() : getDataLogged(); }
	
	private static Map<Class<?>, Set<FieldUsage>> getProducers(){
		class Cache{
			private static final Map<Class<?>, Set<FieldUsage>> VAL;
			
			static{
				Map<Class<?>, Set<FieldUsage>> map = new HashMap<>();
				for(var f : getData()){
					for(var typ : f.listFieldTypes()){
						map.computeIfAbsent(typ, t -> new HashSet<>()).add(f);
					}
				}
				map.replaceAll((k, v) -> Set.copyOf(v));
				VAL = Map.copyOf(map);
			}
		}
		return Cache.VAL;
	}
	
	static Collection<Class<?>> getWrappers(){
		class Cache{
			private static final Collection<Class<?>> VAL;
			
			static{
				var uni = getSealedUniverse(NullFlagCompanyField.class, true).orElseThrow();
				VAL = Collections.unmodifiableSet(new LinkedHashSet<>(
					Iters.from(uni.universe())
					     .flatOptionals((Class<NullFlagCompanyField> fieldType) -> {
						     if(fieldType.isAnnotationPresent(IOUnsafeValue.Mark.class)){
							     return Optional.empty();
						     }
						     var superC = (ParameterizedType)fieldType.getGenericSuperclass();
						     if(!List.of(NullFlagCompanyField.class, IOFieldWrapper.class).contains(Utils.typeToRaw(superC))){
							     return Optional.empty();
						     }
						     var args = superC.getActualTypeArguments();
						     if(!(args[1] instanceof Class<?> valueType)){
							     return Optional.empty();
						     }
						     return Optional.of(valueType);
					     })
					     .distinct()
					     .sortedBy(Class::getName)
					     .toModList()
				));
			}
		}
		return Cache.VAL;
	}
	
	private static List<FieldUsage> getDataLogged(){
		Log.trace("Waiting for FieldRegistry...");
		var start = System.nanoTime();
		var data  = USAGES.join();
		var end   = System.nanoTime();
		Log.trace("Waited {}ms for FieldRegistry", (end - start)/1000_000D);
		return data;
	}
	
	private FieldRegistry(){ }
	
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
		if(UtilL.instanceOf(Utils.typeToRaw(type), Type.class)){
			throw new IllegalField(
				"fmt", "Directly storing types should not be done as values will fail to load if a class is not present. " +
				       "Please use {}#green instead.", IOType.class.getTypeName()
			);
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
		if(DEBUG_VALIDATION) checkField(field, compatible, res);
		return res;
	}
	
	private static <T extends IOInstance<T>> void checkField(FieldAccessor<T> field, FieldUsage usedUsage, IOField<T, ?> toCheck){
		
		var usesUnsafe  = field.hasAnnotation(IOUnsafeValue.class);
		var makedUnsafe = toCheck.getClass().isAnnotationPresent(IOUnsafeValue.Mark.class);
		if(usesUnsafe != makedUnsafe){
			throw new ShouldNeverHappenError(toCheck.getClass().getTypeName() + " has IOUnsafeValue but the type is not marked as such");
		}
		
		var types = usedUsage.listFieldTypes();
		if(!types.contains(toCheck.getClass())){
			throw new RuntimeException(
				"The IOField type is not listed within the FieldUsage field types!\n" +
				"Field type:   " + toCheck.getClass().getName() + "\n" +
				"Listed types: " + Iters.from(types).joinAsStr(Class::getName)
			);
		}
		
		var ann  = GetAnnotation.from(field);
		var type = field.getGenericType(null);
		
		var allCompatible = Iters.from(getData()).filter(usage -> usage.isCompatible(type, ann)).asCollection();
		if(allCompatible.size()>1){
			throw new RuntimeException(
				"Ambiguous usage picking\n" +
				allCompatible.joinAsStr("\n", u -> "\t" + u.getClass().getName())
			);
		}
	}
	
	private static final Set<Class<? extends Annotation>> CONSUMABLE_ANNOTATIONS
		= Iters.from(FieldCompiler.ANNOTATION_TYPES)
		       .filter(t -> !List.of(IOValue.class, IODependency.class, IOValue.OverrideType.class).contains(t))
		       .toSet();
	
	private static <T extends IOInstance<T>> Set<FieldUsage> findUsages(IOField<T, ?> field){
		var             producers = getProducers();
		Set<FieldUsage> usages    = producers.get(field.getClass());
		if(usages == null) usages = Iters.entries(producers)
		                                 .firstMatching(e -> UtilL.instanceOf(field.getClass(), e.getKey()))
		                                 .map(Map.Entry::getValue).orElse(null);
		return usages;
	}
	
	static <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(IOField<T, ?> field){
		var usages = findUsages(field);
		if(usages == null) return List.of();
		//noinspection unchecked
		var type = (Class<IOField<T, ?>>)field.getClass();
		var acc  = field.getAccessor();
		
		Set<VirtualFieldDefinition<T, ?>> result     = new HashSet<>();
		Set<Class<? extends Annotation>>  activeAnns = Iters.from(CONSUMABLE_ANNOTATIONS).filter(acc::hasAnnotation).toModSet();
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
			throw new IllegalAnnotation(
				"fmt", "Field {}#yellow has incompatible annotation(s):\n{}", field,
				Iters.from(activeAnns).joinAsStr("\n", annType -> {
					var usageAnn = annType.getAnnotation(AnnotationUsage.class);
					if(usageAnn == null) return Log.fmt("\t{}#red", annType.getSimpleName());
					return Log.fmt("\t{}#red:\t{}#yellow", annType.getSimpleName(), usageAnn.value());
				})
			);
		}
		
		return Iters.from(result).sortedBy(VirtualFieldDefinition::name).toModList();
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
		throw new IllegalField("fmt", "Unable to find implementation of {}#yellow from {}#red", IOField.class.getSimpleName(), typeName);
	}
}
