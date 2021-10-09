package com.lapisseqa.cfs.run;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.objects.collections.IOMap;

import java.util.HashMap;

public class ReferenceMemoryIOMap<K, V> implements IOMap<K, V>{
	
	private final HashMap<K, V> data=new HashMap<>();
	
	@Override
	public long size(){
		return data.size();
	}
	
	@Override
	public Entry<K, V> getEntry(K key){
		if(!data.containsKey(key)) return null;
		
		var k=data.get(key);
		return new Entry.Abstract<>(){
			@Override
			public K getKey(){
				return key;
			}
			@Override
			public V getValue(){
				return k;
			}
			@Override
			public void set(V value){
				data.put(key, value);
			}
		};
	}
	
	@Override
	public IterablePP<Entry<K, V>> entries(){
		return ()->data.entrySet().stream().map(Entry::viewOf).iterator();
	}
	
	@Override
	public void put(K key, V value){
		data.put(key, value);
	}
	
	@Override
	public String toString(){
		return IOMap.toString(this);
	}
}
