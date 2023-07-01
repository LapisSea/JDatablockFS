package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.LateInit;
import com.lapissea.util.UtilL;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

final class FieldRegistry{
	
	private static final LateInit.Safe<List<IOField.FieldUsage>> USAGES = Runner.async(new Supplier<>(){
		@Override
		public List<IOField.FieldUsage> get(){
			Log.trace("{#yellowBrightDiscovering IOFields#}");
			
			var tasks = new ConcurrentLinkedDeque<LateInit.Safe<Optional<Map.Entry<Class<?>, List<IOField.FieldUsage>>>>>();
			scan(IOField.class, tasks);
			
			var scanned = new HashMap<Class<?>, List<IOField.FieldUsage>>();
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
		
		private static void scan(Class<?> type, Deque<LateInit.Safe<Optional<Map.Entry<Class<?>, List<IOField.FieldUsage>>>>> tasks){
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
				for(var sub : type.getPermittedSubclasses()){
					tasks.add(Runner.async(() -> {
						scan(sub, tasks);
						return Optional.empty();
					}));
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
		
		private static Optional<Map.Entry<Class<?>, List<IOField.FieldUsage>>> getFieldUsage(Class<?> type){
			if(type == IOField.class) return Optional.empty();
			var usageClasses =
				Optional.ofNullable(type.getDeclaredAnnotation(IOField.FieldUsageRef.class))
				        .map(IOField.FieldUsageRef::value).filter(a -> a.length>0).map(List::of)
				        .orElseGet(() -> Arrays.stream(type.getDeclaredClasses())
				                               .filter(c -> UtilL.instanceOf(c, IOField.FieldUsage.class))
				                               .map(c -> {
					                               //noinspection unchecked
					                               return (Class<IOField.FieldUsage>)c;
				                               })
				                               .toList());
			
			if(usageClasses.isEmpty()){
				return Optional.empty();
			}
			
			return Optional.of(Map.entry(type, usageClasses.stream().map(u -> make(u)).toList()));
		}
		
		private static IOField.FieldUsage make(Class<IOField.FieldUsage> usageClass){
			Constructor<IOField.FieldUsage> constr;
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
	
	private static List<IOField.FieldUsage> getData(){
		if(USAGES.isInitialized() || !Log.TRACE){
			return USAGES.get();
		}
		return getLogged();
	}
	private static List<IOField.FieldUsage> getLogged(){
		var start = System.nanoTime();
		var data  = USAGES.get();
		var end   = System.nanoTime();
		Log.trace("Waited {}ms for FieldRegistry", (end - start)/1000_000D);
		return data;
	}
	
	public static void init(){ }
	
	private FieldRegistry()  { }
	
	static void requireCanCreate(Type type, GetAnnotation annotations){
		for(var usage : getData()){
			if(usage.isCompatible(type, annotations)){
				return;
			}
		}
		throw fail(type.getTypeName());
	}
	
	static <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
		var ann  = GetAnnotation.from(field);
		var type = field.getGenericType(genericContext);
		
		IOField.FieldUsage compatible;
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
		
		return compatible.create(field, genericContext);
	}
	
	private static IllegalField fail(String typeName){
		throw new IllegalField("Unable to find implementation of " + IOField.class.getSimpleName() + " from " + typeName);
	}
}
