package com.lapissea.dfs.run;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOMap;

import java.util.HashMap;
import java.util.Map;

public class ReferenceMemoryIOMap<K, V> implements IOMap<K, V>{
	
	private final HashMap<K, V> data = new HashMap<>();
	
	@Override
	public long size(){
		return data.size();
	}
	
	@Override
	public IOEntry.Modifiable<K, V> getEntry(K key){
		if(!data.containsKey(key)) return null;
		
		var k = data.get(key);
		return new IOEntry.Modifiable.Abstract<>(){
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
	public IOIterator.Iter<IOEntry<K, V>> iterator(){
		var src = data.entrySet().iterator();
		return new IOIterator.Iter<>(){
			@Override
			public boolean hasNext(){
				return src.hasNext();
			}
			@Override
			public IOEntry<K, V> ioNext(){
				return IOEntry.viewOf(src.next());
			}
		};
	}
	
	@Override
	public void put(K key, V value){
		data.put(key, value);
	}
	@Override
	public void putAll(Map<K, V> values){
		data.putAll(values);
	}
	@Override
	public boolean remove(K key){
		var had = data.containsKey(key);
		data.remove(key);
		return had;
	}
	
	@Override
	public void clear(){
		data.clear();
	}
	
	@Override
	public String toString(){
		return IOMap.toString(this);
	}
}
