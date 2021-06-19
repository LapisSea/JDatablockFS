package com.lapissea.cfs.objects.collections;

import java.io.IOException;

public interface IOMap<K, V>{
	
	interface Entry<K, V>{
		K getKey();
		
		V get() throws IOException;
		void set(V value) throws IOException;
	}
	
	Entry<K, V> getEntry(K key) throws IOException;
	
	default V get(K key) throws IOException{
		return getEntry(key).get();
	}
	
	default void put(K key, V value) throws IOException{
		getEntry(key).set(value);
	}
	
}
