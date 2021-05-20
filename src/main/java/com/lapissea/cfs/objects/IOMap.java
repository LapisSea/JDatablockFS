package com.lapissea.cfs.objects;

public interface IOMap<K, V>{
	
	V getValue(K key);
	
	void putValue(K key, V value);
	
	default void validate(){}
	
	void clear();
	
	
}
