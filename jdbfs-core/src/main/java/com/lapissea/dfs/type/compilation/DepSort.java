package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;
import com.lapissea.util.ZeroArrays;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A topological dependency sort algorithm
 */
public class DepSort<T>{
	
	public static final class CycleException extends RuntimeException{
		
		public final Index cycle;
		
		private CycleException(Index cycle){
			super("Dependency cycle detected! Cycle index: " + cycle);
			this.cycle = cycle;
		}
	}
	
	private static class IntFill{
		private int[] data;
		private int   cursor;
		
		IntFill(){
			this.data = new int[2];
		}
		void add(int i){
			if(data.length == cursor) data = Arrays.copyOf(data, data.length*2);
			data[cursor] = i;
			cursor++;
		}
		
		int size(){
			return cursor;
		}
		void clear(){
			cursor = 0;
		}
		boolean contains(int val){
			for(int i = 0; i<cursor; i++){
				if(val == data[i]) return true;
			}
			return false;
		}
		Index toIndex(){
			return new Index(Arrays.copyOf(data, cursor));
		}
	}
	
	private final class TSort{
		private final IntFill stack;
		private final IntFill index;
		private final BitSet  visited;
		
		TSort(){
			stack = new IntFill();
			index = new IntFill();
			visited = new BitSet();
		}
		private boolean visit(int i){
			if(visited.get(i)) return false;
			visited.set(i);
			return true;
		}
		private void clear(){
			stack.clear();
			index.clear();
			visited.clear();
		}
		
		Index sort(IterableIntPP roots){
			if(data.isEmpty()) return new Index(ZeroArrays.ZERO_INT);
			
			roots.forEach(i -> {
				stack.clear();
				walkGraph(i);
			});
			if(index.size() != data.size()) throw new RuntimeException("index not full");
			
			var result = index.toIndex();
			clear();
			return result;
		}
		
		private void walkGraph(int i) throws CycleException{
			if(visited.get(i)) return;
			
			if(stack.contains(i)){
				throw new CycleException(stack.toIndex());
			}
			stack.add(i);
			
			getDependencies.apply(data.get(i)).forEach(index -> {
				Objects.checkIndex(index, data.size());
				walkGraph(index);
			});
			
			if(visit(i)){
				index.add(i);
			}else throw new RuntimeException();
		}
	}
	
	private final List<T>                    data;
	private final Function<T, IterableIntPP> getDependencies;
	
	public DepSort(@NotNull List<T> data, @NotNull Function<T, IterableIntPP> getDependencies){
		this.getDependencies = Objects.requireNonNull(getDependencies);
		this.data = Objects.requireNonNull(data);
	}
	
	public Index sort(){
		return sort(Iters.range(0, data.size()));
	}
	
	public final Index sort(Comparator<T> comparator){
		return sort(Iters.range(0, data.size()).sorted(data::get, comparator));
	}
	
	public Index sort(IterableIntPP orderSuggestion){
		return new TSort().sort(orderSuggestion);
	}
	
}
