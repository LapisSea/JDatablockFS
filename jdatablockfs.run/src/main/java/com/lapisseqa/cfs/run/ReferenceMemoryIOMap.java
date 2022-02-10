package com.lapisseqa.cfs.run;

import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

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
	public Stream<Entry<K, V>> stream(){
		return data.entrySet().stream().map(Entry::viewOf);
	}
	
	@Override
	public void put(K key, V value){
		data.put(key, value);
	}
	@Override
	public void putAll(Map<K, V> values) throws IOException{
		data.putAll(values);
	}
	
	@Override
	public String toString(){
		return IOMap.toString(this);
	}
}
