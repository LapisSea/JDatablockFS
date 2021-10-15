package com.lapisseqa.cfs.run;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;

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
			public Entry<K, V> getEntry(K key) throws IOException{
				return a.getEntry(key);
			}
			@Override
			public IterablePP<Entry<K, V>> entries(){
				return a.entries();
			}
			@Override
			public void put(K key, V value) throws IOException{
				a.put(key, value);
				b.put(key, value);
				test();
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
		};
	}
	
}