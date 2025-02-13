package com.lapissea.dfs.internal;

import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;

import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings({"unchecked", "rawtypes"})
public final class Preload{
	
	public static <T extends IOInstance<T>> void preloadPipe(Class<T> c, Class<? extends StructPipe> pipeClass){
		Objects.requireNonNull(pipeClass);
		task(c, pipeClass, null, null, null);
	}
	
	public static void preloadFn(Class<?> c, String name, Object... args){
		task(c, null, Objects.requireNonNull(name), Objects.requireNonNull(args), null);
	}
	public static void preload(Class<?> c){
		task(c, null, null, null, null);
	}
	public static void preload(Class<?> c, MethodHandles.Lookup lookup){
		task(c, null, null, null, lookup);
	}
	private static void task(Class<?> c, Class<? extends StructPipe> pipeClass, String functionName, Object[] args, MethodHandles.Lookup cLookup){
		CompletableFuture.runAsync(() -> {
			try{
				if(pipeClass != null){
					StructPipe.of((Class)pipeClass, (Struct)Struct.ofUnknown(c, StagedInit.STATE_DONE), StagedInit.STATE_DONE);
				}else if(functionName != null){
					Class[] argTypes = new Class[args.length];
					for(int i = 0; i<args.length; i++){
						argTypes[i] = args[i] == null? Object.class : args[i].getClass();
					}
					if(functionName.equals("<init>")){
						var ctor = c.getConstructor(argTypes);
						ctor.newInstance(args);
					}else{
						var fn = c.getDeclaredMethod(functionName, argTypes);
						fn.setAccessible(true);
						fn.invoke(null, args);
					}
				}else{
					if(cLookup != null){
						cLookup.ensureInitialized(c);
					}else{
						MethodHandles.lookup().ensureInitialized(c);
					}
				}
			}catch(Throwable e){
				e.printStackTrace();
			}
		}, Thread::startVirtualThread);
	}
	
}
