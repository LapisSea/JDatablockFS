package com.lapissea.cfs.run;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

public class Splitter{
	
	static <K, V> IOMap<K, V> map(IOMap<K, V> a, IOMap<K, V> b, UnsafeBiConsumer<IOMap<K, V>, IOMap<K, V>, IOException> event){
		return new IOMap<>(){
			
			private void test() throws IOException{
				event.accept(a, b);
			}
			
			@Override
			public long size(){
				return a.size();
			}
			@Override
			public IOEntry.Modifiable<K, V> getEntry(K key) throws IOException{
				return a.getEntry(key);
			}
			@Override
			public Stream<IOEntry<K, V>> stream(){
				return a.stream();
			}
			@Override
			public void put(K key, V value) throws IOException{
				a.put(key, value);
				b.put(key, value);
				test();
			}
			@Override
			public void putAll(Map<K, V> values) throws IOException{
				a.putAll(values);
				b.putAll(values);
				test();
			}
			@Override
			public boolean remove(K key) throws IOException{
				var removed=a.remove(key);
				b.remove(key);
				test();
				return removed;
			}
			@Override
			public boolean containsKey(K key) throws IOException{
				return a.containsKey(key);
			}
			@Override
			public V get(K key) throws IOException{
				return a.get(key);
			}
			
			@Override
			public String toString(){
				return a.toString();
			}
		};
	}
	
	static <E> IOList<E> list(IOList<E> a, IOList<E> b, UnsafeBiConsumer<IOList<E>, IOList<E>, IOException> event){
		return new IOList<>(){
			
			private void test() throws IOException{
				event.accept(a, b);
			}
			
			@Override
			public long size(){
				return a.size();
			}
			@Override
			public E get(long index) throws IOException{
				return a.get(index);
			}
			@Override
			public void set(long index, E value) throws IOException{
				a.set(index, value);
				b.set(index, value);
				test();
			}
			@Override
			public void add(long index, E value) throws IOException{
				a.add(index, value);
				b.add(index, value);
				event.accept(a, b);
			}
			@Override
			public void add(E value) throws IOException{
				a.add(value);
				b.add(value);
				test();
			}
			@Override
			public void addAll(Collection<E> values) throws IOException{
				a.addAll(values);
				b.addAll(values);
				test();
			}
			@Override
			public void remove(long index) throws IOException{
				a.remove(index);
				b.remove(index);
				test();
			}
			@Override
			public E addNew(UnsafeConsumer<E, IOException> initializer) throws IOException{
				var val=a.addNew(initializer);
				b.addNew(initializer);
				test();
				return val;
			}
			@Override
			public void addMultipleNew(long count, UnsafeConsumer<E, IOException> initializer) throws IOException{
				a.addMultipleNew(count, initializer);
				b.addMultipleNew(count, initializer);
				test();
			}
			@Override
			public void clear() throws IOException{
				a.clear();
				b.clear();
			}
			@Override
			public void requestCapacity(long capacity) throws IOException{
				a.requestCapacity(capacity);
				b.requestCapacity(capacity);
				test();
			}
			@Override
			public void trim() throws IOException{
				a.trim();
				b.trim();
				test();
			}
			@Override
			public long getCapacity() throws IOException{
				return a.getCapacity();
			}
			@Override
			public String toString(){
				return a.toString();
			}
		};
	}
	
}
