package com.lapissea.cfs;

import com.lapissea.util.TextUtil;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class Index{
	
	private abstract sealed class Bound<T> extends AbstractList<T> permits BList, BArr{
		protected abstract T getUnmapped(int index);
		
		@Override
		public final T get(int index){
			return getUnmapped(map(index));
		}
		
		@Override
		public int size(){
			return Index.this.size();
		}
	}
	
	private final class BList<T> extends Bound<T>{
		private final List<T> unbound;
		public BList(List<T> data){ this.unbound = data; }
		
		@Override
		protected T getUnmapped(int index){
			return unbound.get(index);
		}
	}
	
	private final class BArr<T> extends Bound<T>{
		private final T[] unbound;
		public BArr(T[] data){ this.unbound = data; }
		
		@Override
		protected T getUnmapped(int index){
			return unbound[index];
		}
	}
	
	private final int[] data;
	
	public Index(int[] rawIndex){
		this.data = Objects.requireNonNull(rawIndex);
	}
	
	public int size(){
		return data.length;
	}
	
	public int map(int i){
		return data[i];
	}
	
	public <T> List<T> mapData(List<T> data){
		return new BList<>(data);
	}
	public <T> List<T> mapData(T[] data){
		return new BArr<>(data);
	}
	
	@Override
	public String toString(){
		return TextUtil.toString(data);
	}
	public IntStream stream(){
		return IntStream.of(data);
	}
}
