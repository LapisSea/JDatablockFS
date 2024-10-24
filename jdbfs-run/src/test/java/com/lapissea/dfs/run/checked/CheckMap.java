package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeSupplier;
import org.testng.Assert;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class CheckMap<K, V> implements IOMap<K, V>{
	
	private final IOMap<K, V> data;
	private final Map<K, V>   base = new HashMap<>();
	
	public CheckMap(IOMap<K, V> data){
		this.data = data;
		
		for(var e : data){
			base.put(e.getKey(), e.getValue());
		}
	}
	
	private void checkData(){
		var allData = new HashMap<K, V>();
		for(IOEntry<K, V> e : data){
			if(allData.put(e.getKey(), e.getValue()) != null){
				Assert.fail("Duplicate key: " + e.getKey());
			}
		}
		
		Assert.assertEquals(allData, base);
	}
	
	@Override
	public boolean isEmpty(){
		var a = data.isEmpty();
		var b = base.isEmpty();
		Assert.assertEquals(a, b, "isEmpty");
		return a;
	}
	@Override
	public boolean containsKey(K key) throws IOException{
		var a = data.containsKey(key);
		var b = base.containsKey(key);
		Assert.assertEquals(a, b, "containsKey");
		return a;
	}
	@Override
	public Iterator<IOEntry<K, V>> iterator(){
		var ia        = data.iterator();
		var remaining = new HashMap<>(base);
		
		return new Iterator<>(){
			K lastRet;
			@Override
			public boolean hasNext(){
				var a = ia.hasNext();
				var b = !remaining.isEmpty();
				Assert.assertEquals(a, b, "iter.hasNext");
				return a;
			}
			@Override
			public IOEntry<K, V> next(){
				var a    = ia.next();
				var bVal = remaining.remove(a.getKey());
				var b    = IOEntry.of(a.getKey(), bVal);
				Assert.assertEquals(a, b, "iter.next");
				lastRet = a.getKey();
				return a;
			}
			@Override
			public void remove(){
				ia.remove();
				base.remove(lastRet);
				checkData();
			}
		};
	}
	
	@Override
	public V computeIfAbsent(K key, UnsafeSupplier<V, IOException> compute) throws IOException{
		var a = data.computeIfAbsent(key, compute);
		var b = base.computeIfAbsent(key, k -> {
			try{
				return compute.get();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		});
		Assert.assertEquals(a, b, "computeIfAbsent");
		return a;
	}
	@Override
	public V computeIfAbsent(K key, UnsafeFunction<K, V, IOException> compute) throws IOException{
		var a = data.computeIfAbsent(key, compute);
		var b = base.computeIfAbsent(key, k -> {
			try{
				return compute.apply(k);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		});
		Assert.assertEquals(a, b, "computeIfAbsent");
		return a;
	}
	@Override
	public V get(K key) throws IOException{
		var a = data.get(key);
		var b = base.get(key);
		Assert.assertEquals(a, b, "get");
		return a;
	}
	@Override
	public long size(){
		var a = data.size();
		var b = base.size();
		Assert.assertEquals(a, b, "size");
		return a;
	}
	@Override
	public IOEntry.Modifiable<K, V> getEntry(K key){
		throw NotImplementedException.infer();//TODO: implement CheckMap.getEntry()
	}
	@Override
	public void put(K key, V value) throws IOException{
		data.put(key, value);
		base.put(key, value);
		checkData();
	}
	@Override
	public void putAll(Map<K, V> values) throws IOException{
		data.putAll(values);
		base.putAll(values);
		checkData();
	}
	@Override
	public boolean remove(K key) throws IOException{
		var a = data.remove(key);
		var b = base.remove(key) != null;
		Assert.assertEquals(a, b, "remove");
		checkData();
		return a;
	}
	@Override
	public void clear() throws IOException{
		data.clear();
		base.clear();
		checkData();
	}
	@Override
	public String toString(){
		return data.toString();
	}
}
