package com.lapissea.dfs.utils;

import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class WeakKeyValueMap<K, V>{
	
	public static final class Sync<K, V> extends WeakKeyValueMap<K, V>{
		private final Lock lock = new ReentrantLock();
		@Override
		public V put(K key, V value){
			lock.lock();
			try{
				return super.put(key, value);
			}finally{
				lock.unlock();
			}
		}
		@Override
		public V get(K key){
			lock.lock();
			try{
				return super.get(key);
			}finally{
				lock.unlock();
			}
		}
		@Override
		public V remove(K key){
			lock.lock();
			try{
				return super.remove(key);
			}finally{
				lock.unlock();
			}
		}
		@Override
		public IterablePP<Map.Entry<K, V>> iter(){
			lock.lock();
			try{
				return derefIter(Iters.entries(data).bake());
			}finally{
				lock.unlock();
			}
		}
	}
	
	private static <V> V deref(WeakReference<V> ref){
		return ref == null? null : ref.get();
	}
	
	protected final WeakHashMap<K, WeakReference<V>> data = new WeakHashMap<>();
	
	public V put(K key, V value){
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		return deref(data.put(key, new WeakReference<>(value)));
	}
	
	public V get(K key){
		return deref(data.get(key));
	}
	
	public V remove(K key){
		return deref(data.remove(key));
	}
	
	public IterablePP<Map.Entry<K, V>> iter(){
		return derefIter(Iters.entries(data));
	}
	protected IterablePP<Map.Entry<K, V>> derefIter(IterablePP<Map.Entry<K, WeakReference<V>>> iter){
		return iter.map(e -> {
			var k = e.getKey();
			var v = deref(e.getValue());
			if(k == null || v == null) return null;
			return Map.entry(k, v);
		}).nonNulls();
	}
}
