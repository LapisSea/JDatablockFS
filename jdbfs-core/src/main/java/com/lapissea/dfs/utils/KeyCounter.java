package com.lapissea.dfs.utils;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class KeyCounter<K>{
	private record Val(int count, long stamp){ }
	
	private static final Executor SHRINKER = Executors.newSingleThreadExecutor();
	
	private final Map<K, Val> compilationCount = new ConcurrentHashMap<>();
	private final int         maxSize;
	private final Executor    shrinker;
	private final Runnable    shrinkMulti;
	
	public KeyCounter()           { this(256); }
	public KeyCounter(int maxSize){ this(SHRINKER, maxSize); }
	public KeyCounter(Executor shrinker, int maxSize){
		if(maxSize<=0) throw new IllegalArgumentException("maxSize must be greater than 0");
		this.maxSize = maxSize;
		this.shrinker = shrinker;
		shrinkMulti = () -> {
			while(compilationCount.size()>=maxSize){
				doShrink();
			}
		};
	}
	
	public boolean hasCount(K key){
		return compilationCount.containsKey(key);
	}
	
	public int getCount(K key){
		return compilationCount.getOrDefault(key, new Val(0, 0)).count;
	}
	public int inc(K key){
		if(compilationCount.size()>=maxSize){
			shrink();
		}
		return compilationCount.compute(key, (k, v) -> {
			if(v == null){
				return new Val(1, System.nanoTime());
			}
			return new Val(v.count + 1, v.stamp);
		}).count;
	}
	
	private void shrink(){
		if(compilationCount.size()>maxSize*1.5){
			doShrink();
		}
		shrinker.execute(shrinkMulti);
	}
	private void doShrink(){
		var r = new RawRandom();
		Iters.entries(compilationCount)
		     .min(Comparator.<Map.Entry<K, Val>>comparingInt(e -> e.getValue().count)
		                    .thenComparingInt(e -> r.nextInt()))
		     .map(Map.Entry::getKey)
		     .ifPresent(compilationCount::remove);
	}
}
