package com.lapissea.dfs.type;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.GcDelayer;
import com.lapissea.dfs.utils.KeyCounter;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static com.lapissea.util.ConsoleColors.GREEN_BRIGHT;
import static com.lapissea.util.ConsoleColors.RESET;

final class CompileStructTools{
	
	interface MakeStruct<T extends IOInstance<T>, S extends Struct<T>>{
		S make(Class<T> type, int syncStage);
	}
	
	private static class WaitHolder{
		private boolean wait = true;
	}
	
	private static final ReadWriteClosableLock                STRUCT_CACHE_LOCK = ReadWriteClosableLock.reentrant();
	private static final WeakKeyValueMap<Class<?>, Struct<?>> STRUCT_CACHE      = new WeakKeyValueMap<>();
	private static final Map<Class<?>, WaitHolder>            NON_CONCRETE_WAIT = new HashMap<>();
	private static final Map<Class<?>, Thread>                STRUCT_THREAD_LOG = new HashMap<>();
	
	private static Map<Integer, WeakReference<Struct<?>>[]> STABLE_CACHE = Map.of();
	
	private static final KeyCounter<String> COMPILATION_COUNT = new KeyCounter<>();
	private static final Duration           GC_DELAY          = ConfigDefs.DELAY_COMP_OBJ_GC.resolveLocking();
	private static final GcDelayer          GC_DELAYER        = GC_DELAY.isZero()? null : new GcDelayer();
	
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> Struct<T> getCached(Class<T> instanceClass){
		var       stableRefs = STABLE_CACHE.get(instanceClass.hashCode());
		Struct<T> cached;
		if(stableRefs != null && (cached = getStable(instanceClass, stableRefs)) != null){
			return cached;
		}
		
		boolean noNeedForNonConcrete = noNeedForNonConcrete(instanceClass);
		try(var lock = STRUCT_CACHE_LOCK.read()){
			if(noNeedForNonConcrete){
				cached = (Struct<T>)STRUCT_CACHE.get(instanceClass);
			}else{
				cached = getCachedUnsafe(instanceClass, lock.getLock());
			}
			if(cached == null) return null;
			
			if(++cached.unstableAccess>200 || STABLE_CACHE.isEmpty()){
				cached.unstableAccess = 0;
				rebuildStableCache();
			}
		}
		return cached;
	}
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> getStable(Class<T> instanceClass, WeakReference<Struct<?>>[] stableRefs){
		for(var ref : stableRefs){
			Struct<?> val = ref.get();
			if(val == null) continue;
			if(val.getType() == instanceClass || val.getConcreteType() == instanceClass){
				return (Struct<T>)val;
			}
		}
		return null;
	}
	
	private static void rebuildStableCache(){
		var groups = STRUCT_CACHE.iter().toGrouping(e -> e.getKey().hashCode());
		STABLE_CACHE = Iters.entries(groups).toMap(
			Map.Entry::getKey,
			group -> Iters.from(group.getValue()).map(e -> new WeakReference<>(e.getValue()))
			              .toArray(s -> {
				              //noinspection unchecked
				              return (WeakReference<Struct<?>>[])new WeakReference[s];
			              })
		);
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> getCachedUnsafe(Class<T> instanceClass, Lock lock){
		waitNonConcrete(instanceClass, lock);
		return (Struct<T>)STRUCT_CACHE.get(instanceClass);
	}
	private static <T extends IOInstance<T>> void waitNonConcrete(Class<T> instanceClass, Lock lock){
		if(noNeedForNonConcrete(instanceClass)) return;
		actuallyWaitNonConcrete(instanceClass, lock);
	}
	private static <T extends IOInstance<T>> void actuallyWaitNonConcrete(Class<T> instanceClass, Lock lock){
		var queue = new ArrayDeque<Class<?>>();
		queue.push(instanceClass);
		while(!queue.isEmpty()){
			var cl = queue.pop();
			for(var interf : cl.getInterfaces()){
				var holder = NON_CONCRETE_WAIT.get(interf);
				if(holder != null){
					if(holder.wait){
						recursiveCompileCheck(interf);
					}
					
					while(holder.wait){
						lock.unlock();
						UtilL.sleep(1);
						lock.lock();
					}
				}
				queue.push(interf);
			}
			var sup = cl.getSuperclass();
			if(sup != null && UtilL.instanceOf(sup, IOInstance.Def.class)) queue.push(sup);
		}
	}
	private static <T extends IOInstance<T>> boolean noNeedForNonConcrete(Class<T> instanceClass){
		return instanceClass.isInterface() || !UtilL.instanceOf(instanceClass, IOInstance.Def.class);
	}
	
	
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>, S extends Struct<T>> S compile(Class<T> instanceClass, MakeStruct<T, S> newStruct, int syncStage){
		if(!IOInstance.isInstance(instanceClass)){
			throw new ClassCastException(instanceClass.getName() + " is not an " + IOInstance.class.getSimpleName());
		}
		if(Utils.isInnerClass(instanceClass)){
			throw new IllegalArgumentException(instanceClass.getName() + " is an inner non static class. Did you mean to make the class static?");
		}
		
		boolean needsImpl = IOInstance.Def.isDefinition(instanceClass);
		
		if(!needsImpl){
			if(Modifier.isAbstract(instanceClass.getModifiers())){
				throw new IllegalArgumentException("Can not compile " + instanceClass.getName() + " because it is abstract");
			}
			if(IOInstance.Def.isDefinitionImplementation(instanceClass) && !instanceClass.getName().contains(IOInstance.Def.IMPL_FIELDS_MARK)){
				var unmapped = IOInstance.Def.unmap((Class<? extends IOInstance.Def<?>>)instanceClass);
				if(unmapped.isPresent()){
					return compile((Class<T>)unmapped.get(), newStruct, syncStage);
				}
			}
		}
		
		var encounterKey   = instanceClass.getClassLoader().hashCode() + "/" + instanceClass.getName();
		var encounterCount = COMPILATION_COUNT.getCount(encounterKey);
		var again          = encounterCount>0? " (again #" + encounterCount + ")" : "";
		
		var printLogLevel = ConfigDefs.PRINT_COMPILATION.resolve();
		
		var wh = needsImpl? new WaitHolder() : null;
		
		S struct;
		
		try(var lock = STRUCT_CACHE_LOCK.write()){
			recursiveCompileCheck(instanceClass);
			
			//If class was compiled in another thread this should early exit
			var existing = getCachedUnsafe(instanceClass, lock.getLock());
			if(existing != null) return (S)existing;
			
			//The struct is being synchronously requested and the lock is released
			if(STRUCT_THREAD_LOG.get(instanceClass) != null){
				S cached = waitForDoneCompiling(instanceClass, lock);
				if(cached != null) return cached;
			}
			
			
			if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.JUST_START)){
				Log.log("Requested struct: {}#green{}#greenBright{}", Utils.classPathHeadless(instanceClass), instanceClass.getSimpleName(), again);
			}
			
			try{
				STRUCT_THREAD_LOG.put(instanceClass, Thread.currentThread());
				if(needsImpl) NON_CONCRETE_WAIT.put(instanceClass, wh);
				
				lock.getLock().unlock();
				try{
					struct = newStruct.make(instanceClass, syncStage);
				}finally{
					lock.getLock().lock();
				}
				
				var old = STRUCT_CACHE.put(instanceClass, struct);
				assert old == null;
			}catch(MalformedStruct e){
				e.addSuppressed(new MalformedStruct("fmt", "Failed to compile {}#red", instanceClass.getName()));
				throw e;
			}catch(Throwable e){
				throw new MalformedStruct("fmt", e, "Failed to compile {}#red", instanceClass.getName());
			}finally{
				STRUCT_THREAD_LOG.remove(instanceClass);
			}
		}
		
		var count = COMPILATION_COUNT.inc(encounterKey);
		if(count>1 && GC_DELAYER != null){
			GC_DELAYER.delay(struct, GC_DELAY.multipliedBy(count - 1));
		}
		
		if(needsImpl) struct.runOnState(Struct.STATE_CONCRETE_TYPE, () -> {
			try{
				var     impl = struct.getConcreteType();
				boolean hadOld;
				try(var ignored = STRUCT_CACHE_LOCK.write()){
					hadOld = STRUCT_CACHE.put(impl, struct) != null;
					NON_CONCRETE_WAIT.remove(instanceClass).wait = false;
				}
				if(hadOld) Log.trace("Replaced existing struct {}#yellow in cache", impl);
			}catch(Throwable ignored){ }
		}, null);
		
		
		if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.SMALL)){
			struct.runOnStateDone(
				() -> {
					if(printLogLevel == ConfigDefs.CompLogLevel.FULL){
						Log.log(GREEN_BRIGHT + IOFieldTools.toTableString("Struct compiled: " + struct.cleanFullName(), struct.getFields()) + RESET);
						return;
					}
					var fullName  = struct.getFullName();
					var cleanName = struct.cleanName();
					var path      = fullName.substring(0, fullName.length() - cleanName.length());
					Log.log("Struct compiled: {}#cyan{}#cyanBright{}", path, struct, again);
				},
				e -> Log.warn("Failed to compile struct asynchronously: {}#red\n{}", struct.cleanName(), Utils.errToStackTraceOnDemand(e))
			);
		}
		
		return struct;
	}
	private static <T extends IOInstance<T>, S extends Struct<T>> S waitForDoneCompiling(Class<T> instanceClass, ReadWriteClosableLock.LockSession writeLock){
		var wl = writeLock.getLock();
		wl.unlock();
		while(true){
			UtilL.sleep(1);
			try(var ignore = STRUCT_CACHE_LOCK.read()){
				if(STRUCT_THREAD_LOG.get(instanceClass) == null){
					break;
				}
			}
		}
		wl.lock();
		//noinspection unchecked
		return (S)getCachedUnsafe(instanceClass, wl);
	}
	
	private static void recursiveCompileCheck(Class<?> interf){
		Thread thread = STRUCT_THREAD_LOG.get(interf);
		if(thread != null && thread == Thread.currentThread()){
			throw new RecursiveSelfCompilation("Recursive struct compilation of: " + interf.getTypeName());
		}
	}
}
