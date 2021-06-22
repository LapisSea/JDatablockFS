package com.lapissea.cfs.type;

import com.lapissea.cfs.Index;
import com.lapissea.util.ZeroArrays;

import java.io.Serial;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

public class DepSort<T>{
	
	public static class CycleException extends RuntimeException{
		@Serial
		private static final long serialVersionUID=-3414961779662539033L;
		
		public final Index cycle;
		
		private CycleException(Index cycle){
			super("Dependency cycle detected! Cycle index: "+cycle);
			this.cycle=cycle;
		}
	}
	
	private static class IntFill{
		private int[] data;
		private int   cursor;
		
		IntFill(){
			this.data=new int[2];
		}
		void add(int i){
			if(data.length==cursor) data=Arrays.copyOf(data, data.length*3/2);
			data[cursor]=i;
			cursor++;
		}
		
		int size(){
			return cursor;
		}
		void clear(){
			cursor=0;
		}
		boolean contains(int val){
			for(int i=0;i<cursor;i++){
				if(val==data[i]) return true;
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
			stack=new IntFill();
			index=new IntFill();
			visited=new BitSet();
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
		
		Index sort(IntStream roots){
			if(data.isEmpty()) return new Index(ZeroArrays.ZERO_INT);
			
			roots.forEach(i->{
				stack.clear();
				walkGraph(i);
			});
			if(index.size()!=data.size()) throw new RuntimeException("index not full");
			
			var result=index.toIndex();
			clear();
			return result;
		}
		
		private void walkGraph(int i) throws CycleException{
			if(visited.get(i)) return;
			
			if(stack.contains(i)){
				throw new CycleException(stack.toIndex());
			}
			stack.add(i);
			
			getDependencies(i).forEach(index->{
				Objects.checkIndex(index, data.size());
				walkGraph(index);
			});
			
			if(visit(i)){
				index.add(i);
			}else throw new RuntimeException();
		}
	}
	
	private final List<T>                data;
	private final Function<T, IntStream> getDependencies;
	
	public DepSort(List<T> data, Function<T, IntStream> getDependencies){
		this.getDependencies=getDependencies;
		this.data=data;
	}
	
	public Index sort(){
		return sort(IntStream.range(0, data.size()));
	}
	
	public final Index sort(Comparator<T> comparator){
		return sort(IntStream.range(0, data.size())
		                     .boxed()
		                     .sorted((a, b)->comparator.compare(data.get(a), data.get(b)))
		                     .mapToInt(i->i));
	}
	
	public Index sort(IntStream orderSuggestion){
		int[] arr=orderSuggestion.toArray();
		
		return new TSort().sort(Arrays.stream(arr));
	}
	
	private IntStream getDependencies(int i){
		return getDependencies.apply(data.get(i));
	}
	
}
