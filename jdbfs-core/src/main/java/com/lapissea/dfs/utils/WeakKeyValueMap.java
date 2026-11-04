package com.lapissea.dfs.utils;

import com.lapissea.iterableplus.IterablePP;
import com.lapissea.iterableplus.Iters;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class WeakKeyValueMap<K, V>{
	
	public static final class Sync<K, V> extends WeakKeyValueMap<K, V>{
		private final ReadWriteLock lock = new ReentrantReadWriteLock();
		@Override
		public V put(K key, V value){
			var lock = this.lock.writeLock();
			lock.lock();
			try{
				return super.put(key, value);
			}finally{
				lock.unlock();
			}
		}
		@Override
		public V get(K key){
			var lock = this.lock.readLock();
			lock.lock();
			try{
				return super.get(key);
			}finally{
				lock.unlock();
			}
		}
		@Override
		public V remove(K key){
			var lock = this.lock.writeLock();
			lock.lock();
			try{
				return super.remove(key);
			}finally{
				lock.unlock();
			}
		}
		@Override
		public IterablePP<Map.Entry<K, V>> iter(){
			var lock = this.lock.readLock();
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
	
	private final   ReferenceQueue<V>                queue = new ReferenceQueue<>();
	protected final WeakHashMap<K, WeakReference<V>> data  = new WeakHashMap<>();
	
	public V put(K key, V value){
		if(queue.poll() != null){
			cleanEmptyValues();
		}
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		return deref(data.put(key, new WeakReference<>(value, queue)));
	}
	
	private void cleanEmptyValues(){
		while(queue.poll() != null) ;
		data.entrySet().removeIf(e -> e.getValue().get() == null);
	}
	
	public int size(){ return data.size(); }
	
	public V get(K key){
		return deref(data.get(key));
	}
	
	public V remove(K key){
		return deref(data.remove(key));
	}
	
	public IterablePP<Map.Entry<K, V>> iter(){
		return derefIter(Iters.entries(data));
	}
	public IterablePP<K> iterKey(){
		return iter().map(Map.Entry::getKey);
	}
	public IterablePP<V> iterVal(){
		return iter().map(Map.Entry::getValue);
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
