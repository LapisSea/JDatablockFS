package com.lapissea.dfs.utils;

import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public class WeakKeyValueMap<K, V>{
	
	private static <V> V deref(WeakReference<V> ref){
		return ref == null? null : ref.get();
	}
	
	private final WeakHashMap<K, WeakReference<V>> data = new WeakHashMap<>();
	
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
		return Iters.entries(data).map(e -> {
			var k = e.getKey();
			var v = deref(e.getValue());
			if(k == null || v == null) return null;
			return Map.entry(k, v);
		}).nonNulls();
	}
}
