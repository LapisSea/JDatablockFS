package com.lapissea.cfs;

import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.SizeDescriptor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

import static java.util.stream.Collectors.toUnmodifiableMap;

public final class SealedUtil{
	
	public record SealedInstanceUniverse<T extends IOInstance<T>>(Class<T> root, Map<Class<T>, StructPipe<T>> pipeMap){
		public static Optional<SealedInstanceUniverse<?>> ofUnknown(SealedUniverse<?> universe){
			if(IOInstance.isInstance(universe)){
				//noinspection rawtypes,unchecked
				return Optional.of(new SealedInstanceUniverse<>((SealedUniverse)universe));
			}
			return Optional.empty();
		}
		public static <T extends IOInstance<T>> Optional<SealedInstanceUniverse<T>> of(SealedUniverse<T> universe){
			if(IOInstance.isInstance(universe)){
				return Optional.of(new SealedInstanceUniverse<>(universe));
			}
			return Optional.empty();
		}
		
		public SealedInstanceUniverse(SealedUniverse<T> data){
			this(data.root, data.universe.stream().collect(toUnmodifiableMap(t -> t, StandardStructPipe::of)));
		}
		
		public <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> makeSizeDescriptor(boolean nullable, BiFunction<VarPool<Inst>, Inst, T> get){
			
			var sizes     = pipeMap.values().stream().map(StructPipe::getSizeDescriptor).toList();
			var wordSpace = sizes.stream().map(SizeDescriptor::getWordSpace).reduce(WordSpace::min).orElseThrow();
			var fixed = sizes.stream().map(s -> s.getFixed(wordSpace))
			                 .reduce((a, b) -> a.isPresent() && b.isPresent() && a.getAsLong() == b.getAsLong()?
			                                   a : OptionalLong.empty())
			                 .orElseThrow();
			if(fixed.isPresent()){
				return SizeDescriptor.Fixed.of(wordSpace, fixed.getAsLong());
			}
			
			var minSize = nullable? 0 : sizes.stream().mapToLong(s -> s.getMin(wordSpace)).min().orElseThrow();
			var maxSize = sizes.stream().map(s -> s.getMax(wordSpace)).reduce((a, b) -> Utils.combineIfBoth(a, b, Math::max)).orElseThrow();
			
			return SizeDescriptor.Unknown.of(
				wordSpace, minSize, maxSize,
				(ioPool, prov, inst) -> {
					T val = get.apply(ioPool, inst);
					if(val == null){
						if(!nullable) throw new NullPointerException();
						return 0;
					}
					StructPipe<T> instancePipe = pipeMap.get(val.getClass());
					
					return instancePipe.calcUnknownSize(prov, val, wordSpace);
				}
			);
		}
		
		public boolean calcCanHavePointers(){
			return pipeMap.values().stream()
			              .map(StructPipe::getType)
			              .anyMatch(Struct::getCanHavePointers);
		}
	}
	
	public record SealedUniverse<T>(Class<T> root, Set<Class<T>> universe){
		public SealedUniverse{
			Objects.requireNonNull(root);
			assert isSealedCached(root);
			universe = Set.copyOf(universe);
		}
	}
	
	public static <T> Optional<SealedUniverse<T>> getSealedUniverse(Class<T> type, boolean allowUnbounded){
		return computeSealedUniverse(type, allowUnbounded);
	}
	private static <T> Optional<SealedUniverse<T>> computeSealedUniverse(Class<T> type, boolean allowUnbounded){
		if(!isSealedCached(type)){
			return Optional.empty();
		}
		var universe = new HashSet<Class<T>>();
		if(!type.isInterface() && !Modifier.isAbstract(type.getModifiers())){
			universe.add(type);
		}
		var psbc = getPermittedSubclasses(type);
		for(var sub : psbc){
			if(isSealedCached(sub)){
				var uni = computeSealedUniverse(sub, allowUnbounded);
				if(uni.isEmpty()) return Optional.empty();
				universe.addAll(uni.get().universe);
				continue;
			}
			if(allowUnbounded || Modifier.isFinal(sub.getModifiers())){
				universe.add(sub);
				continue;
			}
			//Non sealed make for an unbounded universe
			return Optional.empty();
		}
		if(universe.isEmpty()) throw new IllegalStateException();
		return Optional.of(new SealedUniverse<>(type, universe));
	}
	
	
	private static final class SNode{
		
		private static final class Val<T>{
			private static final Val<?> NON = new Val<>(false, new WeakReference<>(null));
			
			private final boolean                       sealed;
			private final WeakReference<List<Class<T>>> permittedSubclasses;
			
			private Val(boolean sealed, WeakReference<List<Class<T>>> permittedSubclasses){
				this.sealed = sealed;
				this.permittedSubclasses = permittedSubclasses;
			}
			private static <T> Val<T> of(Class<T> clazz){
				var permittedSubclasses = fetchSubclasses(clazz);
				if(permittedSubclasses == null){
					//noinspection unchecked
					return (Val<T>)NON;
				}
				return new Val<>(true, new WeakReference<>(permittedSubclasses));
			}
		}
		
		private final Map<Class<?>, Val<?>> map    = new WeakHashMap<>();
		private final Thread                thread = Thread.currentThread();
		
		private boolean isSealedCached(Class<?> clazz){
			var b = map.get(clazz);
			if(b == null) map.put(clazz, b = Val.of(clazz));
			return b.sealed;
		}
		
		private <T> List<Class<T>> getPermittedSubclasses(Class<T> clazz){
			//noinspection unchecked
			var b = (Val<T>)map.get(clazz);
			if(b == null) map.put(clazz, b = Val.of(clazz));
			if(!b.sealed) return List.of();
			
			var permittedSubclasses = b.permittedSubclasses.get();
			if(permittedSubclasses == null) permittedSubclasses = recomputePermitted(clazz);
			return permittedSubclasses;
		}
		private <T> List<Class<T>> recomputePermitted(Class<T> clazz){
			var b = Val.of(clazz);
			map.put(clazz, b);
			var permittedSubclasses = b.permittedSubclasses.get();
			if(permittedSubclasses == null){
				permittedSubclasses = Objects.requireNonNull(fetchSubclasses(clazz));
			}
			return permittedSubclasses;
		}
		private static <T> List<Class<T>> fetchSubclasses(Class<T> clazz){
			//noinspection unchecked
			var sbc = (Class<T>[])clazz.getPermittedSubclasses();
			return sbc == null? null : List.of(sbc);
		}
	}
	
	private static final ThreadLocal<SNode> IS_SEALED_THREAD = ThreadLocal.withInitial(SNode::new);
	private static       SNode              LAST_SNODE       = IS_SEALED_THREAD.get();
	
	private static SNode getNode(){
		var last = LAST_SNODE;
		if(Thread.currentThread() == last.thread){
			return last;
		}
		return LAST_SNODE = IS_SEALED_THREAD.get();
	}
	
	public static <T> List<Class<T>> getPermittedSubclasses(Class<T> clazz){
		if(clazz.isArray() || clazz.isPrimitive()){
			return List.of();
		}
		var node = getNode();
		return node.getPermittedSubclasses(clazz);
	}
	
	public static boolean isSealedCached(Class<?> clazz){
		if(clazz.isArray() || clazz.isPrimitive()){
			return false;
		}
		var node = getNode();
		return node.isSealedCached(clazz);
	}
}
