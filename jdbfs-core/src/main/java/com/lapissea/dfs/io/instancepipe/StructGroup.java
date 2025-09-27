package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.GcDelayer;
import com.lapissea.dfs.utils.KeyCounter;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.UtilL;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.util.ConsoleColors.BLUE_BRIGHT;
import static com.lapissea.util.ConsoleColors.CYAN_BRIGHT;
import static com.lapissea.util.ConsoleColors.RESET;

final class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>>{
	
	private final Constructor<P> constructor;
	private final Class<?>       type;
	
	private static class CompileInfo{
		private final ClosableLock lock = ClosableLock.reentrant();
		private       int          recursiveCompilingDepth;
	}
	
	private final Map<Struct<T>, Throwable>   errors = new HashMap<>();
	private final Map<Struct<T>, CompileInfo> locks  = Collections.synchronizedMap(new HashMap<>());
	
	StructGroup(Class<? extends StructPipe<?>> type){
		try{
			//noinspection unchecked
			constructor = (Constructor<P>)type.getConstructor(Struct.class, int.class);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("Failed to get pipe constructor", e);
		}
		this.type = type;
	}
	
	private final WeakKeyValueMap<Struct<T>, P> pipes            = new WeakKeyValueMap.Sync<>();
	private final KeyCounter<String>            compilationCount = new KeyCounter<>();
	private final Duration                      gcDelay          = ConfigDefs.DELAY_COMP_OBJ_GC.resolveLocking();
	private final GcDelayer                     gcDelayer        = gcDelay.isZero()? null : new GcDelayer();
	
	P make(Struct<T> struct, int syncStage){
		var cached = pipes.get(struct);
		if(cached != null) return cached;
		return lockingMake(struct, syncStage);
	}
	
	private P lockingMake(Struct<T> struct, int syncStage){
		var info = locks.computeIfAbsent(struct, __ -> new CompileInfo());
		try(var ignored = info.lock.open()){
			var cached = pipes.get(struct);
			if(cached != null) return cached;
			
			if(info.recursiveCompilingDepth>50){
				throw new RecursiveSelfCompilation();
			}
			info.recursiveCompilingDepth++;
			
			return createPipe(struct, syncStage);
		}finally{
			locks.remove(struct);
		}
	}
	
	private P createPipe(Struct<T> struct, int syncStage){
		var err = errors.get(struct);
		if(err != null) throw err instanceof RuntimeException e? e : new RuntimeException(err);
		
		var printLogLevel = ConfigDefs.PRINT_COMPILATION.resolve();
		
		var encounterKey   = struct.getType().getClassLoader().hashCode() + "/" + struct.getType().getName();
		var prevEncounters = compilationCount.getCount(encounterKey);
		if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.JUST_START)){
			var name     = struct.cleanFullName();
			var smolName = struct.cleanName();
			var again    = prevEncounters>0? " (again #" + prevEncounters + ")" : "";
			Log.trace("Requested pipe({}#greenBright): {}#blue{}#blueBright{}",
			          StructPipe.shortPipeName(type), name.substring(0, name.length() - smolName.length()), smolName, again);
		}
		
		P created;
		try{
			created = newPipe(struct, syncStage);
		}catch(InvocationTargetException|ExceptionInInitializerError outerError){
			var cause = outerError.getCause();
			if(cause == null){
				cause = new IllegalStateException("Exception cause should not be null", outerError);
			}
			errors.put(struct, cause);
			throw UtilL.uncheckedThrow(cause);
		}catch(ReflectiveOperationException e){
			errors.put(struct, e);
			throw new RuntimeException("Failed to instantiate pipe", e);
		}
		
		//TODO: replace put/remove with scoped value as temporary storage before putting. Avoid potentially invalid result
		pipes.put(struct, created);
		if(syncStage == StagedInit.STATE_DONE){
			try{
				created.postValidate();
			}catch(Throwable e){
				pipes.remove(struct);
				errors.put(struct, e);
				throw e;
			}
		}
		
		int count = compilationCount.inc(encounterKey);
		if(count>1 && gcDelayer != null){
			gcDelayer.delay(created, gcDelay.multipliedBy(count - 1));
		}
		
		if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.FULL)){
			created.runOnStateDone(() -> {
				String s = "Compiled: " + struct.getFullName() + (prevEncounters>0? " (again)" : "") + "\n" +
				           "\tPipe type: " + BLUE_BRIGHT + created.getClass().getName() + CYAN_BRIGHT + "\n" +
				           "\tSize: " + BLUE_BRIGHT + created.getSizeDescriptor() + CYAN_BRIGHT + "\n" +
				           "\tReference commands: " + created.getReferenceWalkCommands();
				
				var sFields = created.getSpecificFields();
				
				if(!sFields.equals(struct.getFields())){
					s += "\n" + IOFieldTools.toTableString(created.toString(), created.getSpecificFields());
				}
				
				Log.log(CYAN_BRIGHT + s + RESET);
			}, e -> Log.warn("Failed to compile: {}#yellow asynchronously because:\n\t{}#red", created, Utils.errToStackTraceOnDemand(e)));
		}
		
		return created;
	}
	private P newPipe(Struct<T> struct, int syncStage) throws ReflectiveOperationException{
		var pipe = constructor.newInstance(struct, syncStage);
		if(!ConfigDefs.OPTIMIZED_PIPE.resolveVal()){
			return pipe;
		}
		if(!struct.getType().isAnnotationPresent(StructPipe.Special.class)){
			return pipe;
		}
		if(!(pipe.buildSpecializedImplementation(syncStage) instanceof Match.Some(var specializedImplementation))){
			return pipe;
		}
		
		if(DEBUG_VALIDATION){
			checkPipe(specializedImplementation, pipe);
			return makeCheckedSpecialPipe(struct, syncStage, pipe);
		}
		//noinspection unchecked
		return (P)specializedImplementation;
	}
	
	private static <T extends IOInstance<T>> void checkPipe(StructPipe<T> specializedImplementation, StructPipe<T> basePipe){
		if(!UtilL.instanceOf(specializedImplementation.getClass(), StructPipe.SpecializedImplementation.class)){
			throw new ClassCastException(Log.fmt("Specialized implementation {}#red is not a {}#yellow!", StructPipe.SpecializedImplementation.class));
		}
		if(!UtilL.instanceOf(specializedImplementation.getClass(), basePipe.getClass())){
			throw new ClassCastException(Log.fmt("Specialized implementation must override its generic implementation! {}#red!", basePipe.getClass()));
		}
	}
	
	@SuppressWarnings("unchecked")
	private P makeCheckedSpecialPipe(Struct<T> struct, int syncStage, P specialInst) throws ReflectiveOperationException{
		P   created;
		var check = constructor.newInstance(struct, syncStage);
		created = switch(check){
			case StandardStructPipe<?> p -> (P)new CheckedPipe.Standard<>((StandardStructPipe<T>)check, (StandardStructPipe<T>)specialInst);
			case FixedStructPipe<?> p -> (P)new CheckedPipe.Fixed<>((FixedStructPipe<T>)check, (FixedStructPipe<T>)specialInst);
			default -> check;
		};
		return created;
	}
}
