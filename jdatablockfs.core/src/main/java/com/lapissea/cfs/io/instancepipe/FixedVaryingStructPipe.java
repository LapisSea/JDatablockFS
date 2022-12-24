package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.UnsupportedStructLayout;
import com.lapissea.cfs.internal.ReadWriteClosableLock;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VaryingSize;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class FixedVaryingStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	private static class ProviderReply<T extends IOInstance<T>>{
		
		private record Step(NumberSize max, boolean ptr){ }
		
		private final Struct<T> type;
		
		private List<Step> steps;
		
		private final Map<List<NumberSize>, FixedVaryingStructPipe<T>> cache     = new HashMap<>();
		private final ReadWriteClosableLock                            cacheLock = ReadWriteClosableLock.reentrant();
		
		private ProviderReply(Struct<T> type){
			this.type = type;
		}
		
		private FixedVaryingStructPipe<T> make(VaryingSize.Provider rule) throws UseFixed{
			if(steps == null){
				List<Step> steps = new ArrayList<>();
				var pip = new FixedVaryingStructPipe<>(
					type,
					VaryingSize.Provider.intercept(rule, (max, ptr, actual) -> steps.add(new Step(max, ptr)))
				);
				this.steps = List.copyOf(steps);
				return pip;
			}
			
			List<NumberSize> buff = new ArrayList<>(steps.size());
			for(Step step : steps){
				buff.add(rule.provide(step.max, step.ptr).size);
			}
			
			try(var ignored = cacheLock.read()){
				var cached = cache.get(buff);
				if(cached != null) return cached;
			}
			
			try(var ignored = cacheLock.write()){
				var cached = cache.get(buff);
				if(cached != null) return cached;
				
				Log.trace("Creating new varying pip of {}#cyan with {}#purpleBright", type, buff);
				
				var pipe = new FixedVaryingStructPipe<>(type, VaryingSize.Provider.repeat(buff));
				cache.put(buff, pipe);
				
				return pipe;
			}
		}
		
	}
	
	private static final Map<Struct<?>, UnsupportedStructLayout> FAILS       = new ConcurrentHashMap<>();
	private static final Map<Struct<?>, ProviderReply<?>>        REPLY_CACHE = new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> BaseFixedStructPipe<T> tryVarying(Struct<T> type, VaryingSize.Provider rule){
		{
			var e = FAILS.get(type);
			if(e != null) throw e;
		}
		try{
			if(rule == VaryingSize.Provider.ALL_MAX){
				return FixedStructPipe.of(type, STATE_IO_FIELD);
			}
			
			try{
				return ((ProviderReply<T>)REPLY_CACHE.computeIfAbsent(type, ProviderReply::new)).make(rule);
			}catch(UseFixed e){
				return FixedStructPipe.of(type, STATE_IO_FIELD);
			}
		}catch(UnsupportedStructLayout|WaitException e){
			if(WaitException.unwait(e) instanceof UnsupportedStructLayout us){
				FAILS.put(type, us);
				throw us;
			}
			throw e;
		}
	}
	
	public static final class UseFixed extends Exception{ }
	
	private FixedVaryingStructPipe(Struct<T> type, VaryingSize.Provider rule) throws UseFixed{
		super(type, (t, structFields) -> {
			if(rule == VaryingSize.Provider.ALL_MAX){
				throw new UseFixed();
			}
			Set<IOField<T, ?>> sizeFields = sizeFieldStream(structFields).collect(Collectors.toSet());
			
			boolean[] effectivelyAllMax = {true};
			var snitchRule = VaryingSize.Provider.intercept(rule, (max, ptr, actual) -> {
				if(actual.size != max) effectivelyAllMax[0] = false;
			});
			
			var result = fixedFields(t, structFields, sizeFields::contains, f -> {
				return f.forceMaxAsFixedSize(snitchRule);
			});
			if(effectivelyAllMax[0]){
				throw new UseFixed();
			}
			return result;
		}, true);
		if(type instanceof Struct.Unmanaged){
			throw new IllegalArgumentException("Unmanaged types are not supported");
		}
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		T instance = getType().make();
		read(provider, src, instance, genericContext);
	}
}
