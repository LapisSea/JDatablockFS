package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.UnsupportedStructLayout;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.compilation.helpers.ProxyBuilder;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FixedVaryingStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	private static final class ProviderReply<T extends IOInstance<T>>{
		
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
				record State(List<Step> steps, StringBuilder sb){ }
				var intercept = VaryingSize.Provider.intercept(
					rule, (max, ptr, actual, s) -> {
						s.steps.add(new Step(max, ptr));
						s.sb.append(actual.size.shortName);
					},
					new State(new ArrayList<>(), new StringBuilder()),
					s -> new State(new ArrayList<>(s.steps), new StringBuilder(s.sb))
				);
				var pipe  = new FixedVaryingStructPipe<>(type, intercept);
				var state = intercept.getState();
				pipe.sizesStr = state.sb.toString();
				this.steps = List.copyOf(state.steps);
				return pipe;
			}
			
			List<NumberSize> buff = new ArrayList<>(steps.size());
			for(var step : steps){
				buff.add(rule.provide(step.max, null, step.ptr).size);
			}
			buff = List.copyOf(buff);
			
			try(var ignored = cacheLock.read()){
				var cached = cache.get(buff);
				if(cached != null) return cached;
			}
			
			try(var ignored = cacheLock.write()){
				var cached = cache.get(buff);
				if(cached != null) return cached;
				
				ConfigDefs.CompLogLevel.JUST_START.log("Creating new varying pip of {}#cyan with {}#purpleBright", type, buff);
				
				var pipe = new FixedVaryingStructPipe<>(type, VaryingSize.Provider.repeat(buff));
				pipe.sizesStr = Iters.from(buff).map(NumberSize::shortName).joinAsStr();
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
		}catch(UnsupportedStructLayout us){
			FAILS.put(type, us);
			throw us;
		}
	}
	
	public static final class UseFixed extends Exception{ }
	
	private String sizesStr;
	
	private FixedVaryingStructPipe(Struct<T> type, VaryingSize.Provider rule) throws UseFixed{
		super(type, (t, structFields, testRun) -> {
			if(testRun) throw new DoNotTest();
			if(rule == VaryingSize.Provider.ALL_MAX){
				throw new UseFixed();
			}
			//noinspection rawtypes,unchecked
			FieldSet<T> sizeFields = FieldSet.of((Iterable)sizeFieldStream(structFields));
			
			record State(List<NumberSize> ruleReply, boolean[] effectivelyAllMax){ }
			var snitchRule = VaryingSize.Provider.intercept(
				rule, (max, ptr, actual, state) -> {
					if(actual.size != max) state.effectivelyAllMax[0] = false;
					state.ruleReply.add(actual.size);
				},
				new State(new ArrayList<>(), new boolean[]{true}),
				s -> new State(new ArrayList<>(s.ruleReply), s.effectivelyAllMax.clone())
			);
			
			var result = fixedFields(t, structFields, sizeFields::contains, f -> {
				return f.forceMaxAsFixedSize(snitchRule);
			});
			var state = snitchRule.getState();
			if(state.effectivelyAllMax[0]){
				throw new UseFixed();
			}
			return new PipeFieldCompiler.Result<>(result, state.ruleReply);
		}, true);
		if(type instanceof Struct.Unmanaged){
			throw new IllegalArgumentException("Unmanaged types are not supported");
		}
	}
	
	@Override
	public Class<StructPipe<T>> getSelfClass(){
		//noinspection unchecked,rawtypes
		return (Class)FixedVaryingStructPipe.class;
	}
	
	@Override
	protected StructPipe<ProxyBuilder<T>> createBuilderPipe(Object builderMetadata){
		//noinspection unchecked
		var ruleReply = (List<NumberSize>)builderMetadata;
		var struct    = getType().getBuilderObjType(true);
		
		FixedVaryingStructPipe<ProxyBuilder<T>> pipe;
		try{
			pipe = new FixedVaryingStructPipe<>(struct, VaryingSize.Provider.repeat(ruleReply));
			pipe.sizesStr = Iters.from(ruleReply).map(NumberSize::shortName).joinAsStr();
		}catch(UseFixed e){
			throw new ShouldNeverHappenError(e);
		}
		ConfigDefs.CompLogLevel.SMALL.log("Acquired builder FixedVaryingStructPipe for {}#green with {}#purpleBright", getType(), ruleReply);
		return pipe;
	}
	
	@Override
	protected void postValidate(){ }
	
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
	public String toString(){
		return shortPipeName(getClass()) + "(" + getType().cleanName() + ":" + sizesStr + ")";
	}
}
