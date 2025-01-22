package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckMap<K, V> implements IOMap<K, V>{
	
	private final IOMap<K, V> data;
	private final Map<K, V>   reference = new HashMap<>();
	
	public CheckMap(IOMap<K, V> data){
		this.data = data;
		
		for(var e : data){
			reference.put(e.getKey(), e.getValue());
		}
	}
	
	private void checkData(){
		var allData = new HashMap<K, V>();
		for(IOEntry<K, V> e : data){
			if(allData.put(e.getKey(), e.getValue()) != null){
				throw new AssertionError("Duplicate key: " + e.getKey());
			}
		}
		
		assertThat(allData.size()).as("Number of iterated entries does not match the reported size").isEqualTo(reference.size());
		
		assertThat(allData).as("Data is not the same as the reference").isEqualTo(reference);
	}
	
	@Override
	public boolean isEmpty(){
		var a = data.isEmpty();
		var b = reference.isEmpty();
		assertThat(a).as("isEmpty not matching").isEqualTo(b);
		return a;
	}
	@Override
	public boolean containsKey(K key) throws IOException{
		var a = data.containsKey(key);
		var b = reference.containsKey(key);
		assertThat(a).as(() -> "containsKey for \"" + key + "\" is not the same as the reference").isEqualTo(b);
		return a;
	}
	@Override
	public IOIterator.Iter<IOEntry<K, V>> iterator(){
		var ia        = data.iterator();
		var remaining = new HashMap<>(reference);
		
		return new IOIterator.Iter<>(){
			K lastRet;
			@Override
			public boolean hasNext(){
				var a = ia.hasNext();
				var b = !remaining.isEmpty();
				assertThat(a).as("iter.hasNext not matching").isEqualTo(b);
				return a;
			}
			@Override
			public IOEntry<K, V> ioNext(){
				var a    = ia.next();
				var bVal = remaining.remove(a.getKey());
				var b    = IOEntry.of(a.getKey(), bVal);
				assertThat(a).as("iter.next not matching").isEqualTo(b);
				lastRet = a.getKey();
				return a;
			}
			@Override
			public void remove(){
				ia.remove();
				reference.remove(lastRet);
				checkData();
			}
		};
	}
	
	@Override
	public V computeIfAbsent(K key, UnsafeSupplier<V, IOException> compute) throws IOException{
		var a = data.computeIfAbsent(key, compute);
		var b = reference.computeIfAbsent(key, k -> {
			try{
				return compute.get();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		});
		assertThat(a).as(() -> "computeIfAbsent for \"" + key + "\" is not the same as the reference").isEqualTo(b);
		return a;
	}
	@Override
	public V computeIfAbsent(K key, UnsafeFunction<K, V, IOException> compute) throws IOException{
		var a = data.computeIfAbsent(key, compute);
		var b = reference.computeIfAbsent(key, k -> {
			try{
				return compute.apply(k);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		});
		assertThat(a).as(() -> "computeIfAbsent for \"" + key + "\" is not the same as the reference").isEqualTo(b);
		return a;
	}
	@Override
	public V get(K key) throws IOException{
		var a = data.get(key);
		var b = reference.get(key);
		assertThat(a).as(() -> "value for \"" + key + "\" is not the same as the reference").isEqualTo(b);
		return a;
	}
	@Override
	public long size(){
		var a = data.size();
		var b = reference.size();
		assertThat(a).as("Sizes do not match").isEqualTo(b);
		return a;
	}
	@Override
	public IOEntry.Modifiable<K, V> getEntry(K key){
		throw NotImplementedException.infer();//TODO: implement CheckMap.getEntry()
	}
	@Override
	public void put(K key, V value) throws IOException{
		data.put(key, value);
		reference.put(key, value);
		checkData();
	}
	@Override
	public void putAll(Map<K, V> values) throws IOException{
		data.putAll(values);
		reference.putAll(values);
		checkData();
	}
	@Override
	public boolean remove(K key) throws IOException{
		var a = data.remove(key);
		var b = reference.remove(key) != null;
		assertThat(a).as(() -> "remove for key \"" + key + "\" is not the same as the reference").isEqualTo(b);
		checkData();
		return a;
	}
	@Override
	public void clear() throws IOException{
		data.clear();
		reference.clear();
		checkData();
	}
	@Override
	public String toString(){
		return data.toString();
	}
}
