package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.FieldWalker;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.compilation.WrapperStructs;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class VersionExecutor{
	
	private final List<VersionTransformer<?>>        transformers;
	private final Map<String, VersionTransformer<?>> oldSet;
	
	public VersionExecutor(List<VersionTransformer<?>> transformers){
		this.transformers = List.copyOf(transformers);
		oldSet = this.transformers.stream().collect(Collectors.toMap(t -> t.oldClassName, t -> t));
	}
	
	private final Map<Class<?>, Boolean> skips = new HashMap<>();
	
	public String newToOld(String newName){
		return transformers.stream().filter(t -> t.newClassName.equals(newName)).findAny().map(t -> t.oldClassName).orElse(null);
	}
	public String oldToNew(String oldName){
		var t = oldSet.get(oldName);
		return t == null? null : t.newClassName;
	}
	
	public boolean isTypeSkipAllowed(Class<?> type){
		return skips.computeIfAbsent(type, this::doCalcSkip);
	}
	
	private enum SkipState{
		SKIP, NO_SKIP, RECURSIVE
	}
	
	private boolean doCalcSkip(Class<?> type){
		if(true) return false;//TODO: fully implement skipping
		/* skips.entrySet().stream().collect(Collectors.toMap(
			Map.Entry::getKey,
			e -> e.getValue()? SkipState.SKIP : SkipState.NO_SKIP)
		)*/
		LogUtil.println("CALC " + type);
		var mapping = new HashMap<Class<?>, SkipState>();
		var state   = calcSkip(type, mapping);
		return switch(state){
			case null -> false;
			case SKIP -> {
				yield true;
			}
			case NO_SKIP -> false;
			case RECURSIVE -> throw new ShouldNeverHappenError("Returned state was recursive");
		};
	}
	
	private SkipState calcSkip(Class<?> type, Map<Class<?>, SkipState> skips){
		while(type.isArray()){
			type = type.componentType();
		}
		if(type.isEnum() || SupportedPrimitive.get(type).isPresent() ||
		   type == ChunkPointer.class || WrapperStructs.isWrapperType(type)) return SkipState.SKIP;
		if(type == Object.class) return SkipState.NO_SKIP;
		if(List.of(List.class, ArrayList.class).contains(type)) return null;
		
		if(type instanceof Class<?> c && skips.get(c) == SkipState.NO_SKIP){
			return SkipState.NO_SKIP;
		}
		
		var n = type.getName();
		if(transformers.stream().anyMatch(t -> t.getOldClassName().equals(n))){
			return SkipState.NO_SKIP;
		}
		
		var oState = Struct.tryOf(type).map(struct -> calcStructSkip(skips, struct));
		if(oState.isPresent()) return oState.get();
		
		oState = calcSealedSkip(type, skips);
		if(oState.isPresent()) return oState.get();
		
		throw new NotImplementedException(type.getName());
	}
	
	private Optional<SkipState> calcSealedSkip(Class<?> type, Map<Class<?>, SkipState> skips){
		var o = SealedUtil.getSealedUniverse(type, false)
		                  .flatMap(SealedUtil.SealedInstanceUniverse::ofUnknown);
		if(o.isEmpty()) return Optional.empty();
		var uni = o.get();
		
		if(skips.get(type) == SkipState.RECURSIVE) return Optional.of(SkipState.RECURSIVE);
		for(var pipe : uni.pipeMap().values()){
			var state = calcStructSkip(skips, pipe.getType());
			if(state != SkipState.SKIP) return Optional.of(SkipState.NO_SKIP);
		}
		return Optional.of(SkipState.SKIP);
	}
	
	private SkipState calcStructSkip(Map<Class<?>, SkipState> skips, Struct<?> struct){
		var type = struct.getType();
		if(struct instanceof Struct.Unmanaged<?> unmanaged){
			return SkipState.NO_SKIP;
		}
		
		for(var field : struct.getFields()){
			if(field.typeFlag(IOField.DYNAMIC_FLAG)){
				var typ  = field.getType();
				var skip = calcSealedSkip(typ, skips);
				if(skip.isPresent()){
					switch(skip.get()){
						case SKIP, RECURSIVE -> { continue; }
						case NO_SKIP -> { return SkipState.NO_SKIP; }
					}
				}
				return SkipState.NO_SKIP;
			}
			var typ = field.getGenericType(null);
			if(typ instanceof Class<?> c){
				switch(skips.get(c)){
					case null -> { }
					case SKIP, RECURSIVE -> { continue; }
					case NO_SKIP -> { return SkipState.NO_SKIP; }
				}
			}
			for(var raw : IOType.of(typ).collectRaws(null)){
				var cls = raw.getTypeClass(null);
				switch(skips.get(cls)){
					case null -> { }
					case SKIP, RECURSIVE -> { continue; }
					case NO_SKIP -> { return SkipState.NO_SKIP; }
				}
				skips.put(cls, SkipState.RECURSIVE);
				var skip = calcSkip(cls, skips);
				skips.put(cls, skip);
				if(skip == SkipState.NO_SKIP) return SkipState.NO_SKIP;
			}
		}
		
		return SkipState.SKIP;
	}
	
	public <TV extends IOInstance<TV>> IOInstance<?> versionForward(
		DataProvider provider, TV value, Set<ChunkPointer> chainsToFree
	) throws IOException{
		if(value == null) return null;
		
		FieldWalker.walk(provider, value, new FieldWalker.FieldRecord(){
			@Override
			public <I extends IOInstance<I>> int log(I instance, IOField<I, ?> field) throws IOException{
				Object   val  = null;
				Class<?> type = field.getType();
				
				var dyn = field.typeFlag(IOField.DYNAMIC_FLAG);
				if(dyn){
					val = field.get(null, instance);
					if(val != null) type = val.getClass();
				}
				
				if(isTypeSkipAllowed(type)){
					return FieldWalker.SKIP;
				}
				
				if(IOInstance.isUnmanaged(type)){
					if(!dyn) val = field.get(null, instance);
					if(val != null){
						var unmanaged = (IOInstance.Unmanaged<?>)val;
						
						switch(unmanaged.versionForward(VersionExecutor.this, chainsToFree)){
							case IOInstance.Unmanaged.VersioningType.NOOP ignored -> { }
							case IOInstance.Unmanaged.VersioningType.InPlace ignored -> {
								return FieldWalker.SKIP;
							}
							case IOInstance.Unmanaged.VersioningType.Reallocate reallocate -> {
								var uf = (IOField<I, IOInstance.Unmanaged<?>>)field;
								uf.set(null, instance, reallocate.newValue());
								chainsToFree.addAll(reallocate.chainsToFree());
								return FieldWalker.SAVE|FieldWalker.SKIP;
							}
						}
					}
				}
				
				var transformer = oldSet.get(type.getName());
				if(transformer != null){
					if(!dyn) val = field.get(null, instance);
					
					var transformed = transformer.apply((IOInstance<?>)val);
					
					Log.debug("Versioned {} to {}", val, transformed);
					((IOField<I, Object>)field).set(null, instance, transformed);
					return FieldWalker.SAVE|FieldWalker.CONTINUE;
				}
				return FieldWalker.CONTINUE;
			}
		});
		
		var transformer = oldSet.get(value.getClass().getName());
		if(transformer != null){
			return transformer.apply(value);
		}
		
		return value;
	}
	
	@Override
	public String toString(){
		return transformers.toString();
	}
}
