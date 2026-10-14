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
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Lock;

import static com.lapissea.util.ConsoleColors.GREEN_BRIGHT;
import static com.lapissea.util.ConsoleColors.RESET;

final class CompileStructTools{
	
	interface MakeStruct<T extends IOInstance<T>, S extends Struct<T>>{
		S make(Class<T> type, int syncStage);
	}
	
	
	private static class ValueHolder{
		
		volatile Struct<?> struct;
		
		Thread  thread;
		boolean wait;
	}
	
	private static final ReadWriteClosableLock STRUCT_CACHE_LOCK = ReadWriteClosableLock.reentrant();
	
	private static final ClassValue<ValueHolder> STABLE_CACHE = new ClassValue<>(){
		@Override
		protected ValueHolder computeValue(Class<?> type){
			return new ValueHolder();
		}
	};
	
	
	private static final KeyCounter<String> COMPILATION_COUNT = new KeyCounter<>();
	private static final Duration           GC_DELAY          = ConfigDefs.DELAY_COMP_OBJ_GC.resolveLocking();
	private static final GcDelayer          GC_DELAYER        = GC_DELAY.isZero()? null : new GcDelayer();
	
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> Struct<T> getCached(Class<T> instanceClass){
		var holder = STABLE_CACHE.get(instanceClass);
		if(noNeedForNonConcrete(instanceClass)){
			return (Struct<T>)holder.struct;
		}
		
		try(var lock = STRUCT_CACHE_LOCK.read()){
			return getCachedUnsafe(instanceClass, lock.getLock());
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> getCachedUnsafe(Class<T> instanceClass, Lock lock){
		waitNonConcrete(instanceClass, lock);
		var stableHolder = STABLE_CACHE.get(instanceClass);
		return (Struct<T>)stableHolder.struct;
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
				var holder = STABLE_CACHE.get(interf);
				if(holder.wait){
					recursiveCompileCheck(interf);
				}
				
				while(holder.wait){
					lock.unlock();
					UtilL.sleep(1);
					lock.lock();
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
		
		S struct;
		
		ValueHolder holder = STABLE_CACHE.get(instanceClass);
		try(var lock = STRUCT_CACHE_LOCK.write()){
			recursiveCompileCheck(instanceClass);
			
			//If class was compiled in another thread this should early exit
			var existing = getCachedUnsafe(instanceClass, lock.getLock());
			if(existing != null) return (S)existing;
			
			//The struct is being synchronously requested and the lock is released
			if(holder.thread != null){
				S cached = waitForDoneCompiling(instanceClass, lock);
				if(cached != null) return cached;
			}
			
			
			if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.JUST_START)){
				Log.log("Requested struct: {}#green{}#greenBright{}", Utils.classPathHeadless(instanceClass), instanceClass.getSimpleName(), again);
			}
			
			try{
				holder.thread = Thread.currentThread();
				if(needsImpl) holder.wait = true;
				
				lock.getLock().unlock();
				try{
					struct = newStruct.make(instanceClass, syncStage);
				}finally{
					lock.getLock().lock();
				}
				
				assert holder.struct == null;
				holder.struct = struct;
			}catch(MalformedStruct e){
				e.addSuppressed(new MalformedStruct("fmt", "Failed to compile {}#red", instanceClass.getName()));
				throw e;
			}catch(Throwable e){
				throw new MalformedStruct("fmt", e, "Failed to compile {}#red", instanceClass.getName());
			}finally{
				holder.thread = null;
				//a failed compile never reaches the STATE_CONCRETE_TYPE callback, so need to release waiters here
				if(holder.struct == null){
					holder.wait = false;
				}
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
					hadOld = STABLE_CACHE.get(impl).struct != null;
					STABLE_CACHE.get(impl).struct = struct;
					holder.wait = false;
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
		var holder = STABLE_CACHE.get(instanceClass);
		while(true){
			UtilL.sleep(1);
			try(var ignore = STRUCT_CACHE_LOCK.read()){
				if(holder.thread == null){
					break;
				}
			}
		}
		wl.lock();
		//noinspection unchecked
		return (S)getCachedUnsafe(instanceClass, wl);
	}
	
	private static void recursiveCompileCheck(Class<?> interf){
		var    holder = STABLE_CACHE.get(interf);
		Thread thread = holder.thread;
		if(thread != null && thread == Thread.currentThread()){
			throw new RecursiveSelfCompilation("Recursive struct compilation of: " + interf.getTypeName());
		}
	}
}
