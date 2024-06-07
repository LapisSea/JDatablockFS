package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.objects.ChunkPointer;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

public final class ChunkPtrCollector implements Collector<ChunkPointer, ChunkSet, ChunkSet>{
	
	@Override
	public Supplier<ChunkSet> supplier(){
		return ChunkSet::new;
	}
	
	@Override
	public BiConsumer<ChunkSet, ChunkPointer> accumulator(){
		return ChunkSet::add;
	}
	
	@Override
	public BinaryOperator<ChunkSet> combiner(){
		return (l, r) -> {
			l.addAll(r);
			return l;
		};
	}
	@Override
	public Function<ChunkSet, ChunkSet> finisher(){
		return Function.identity();
	}
	
	@Override
	public Set<Characteristics> characteristics(){
		return EnumSet.of(Characteristics.UNORDERED, Characteristics.IDENTITY_FINISH);
	}
}
