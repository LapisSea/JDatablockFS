package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public interface IOMap<K, V> extends IterablePP<IOMap.Entry<K, V>>{
	
	interface Entry<K, V>{
		
		abstract class Abstract<K, V> implements Entry<K, V>{
			@Override
			public String toString(){
				return this.getClass().getSimpleName()+"{"+Utils.toShortString(getKey())+" = "+Utils.toShortString(getValue())+"}";
			}
			public String toShortString(){
				return "{"+Utils.toShortString(getKey())+" = "+Utils.toShortString(getValue())+"}";
			}
			@Override
			public boolean equals(Object obj){
				return obj==this||
				       obj instanceof Entry<?, ?> e&&
				       Objects.equals(getKey(), e.getKey())&&
				       Objects.equals(getValue(), e.getValue());
			}
			@Override
			public int hashCode(){
				var k=getKey();
				var v=getKey();
				
				int result=1;
				result=31*result+(k==null?0:k.hashCode());
				result=31*result+(v==null?0:v.hashCode());
				return result;
			}
		}
		
		static <K, V> Entry<K, V> of(K key, V value){
			return new Entry.Abstract<>(){
				@Override
				public K getKey(){
					return key;
				}
				@Override
				public V getValue(){
					return value;
				}
				@Override
				public void set(V value){
					throw new UnsupportedOperationException();
				}
			};
		}
		static <K, V> Entry<K, V> viewOf(Map.Entry<K, V> entry){
			return new Entry.Abstract<>(){
				@Override
				public K getKey(){
					return entry.getKey();
				}
				@Override
				public V getValue(){
					return entry.getValue();
				}
				@Override
				public void set(V value){
					entry.setValue(value);
				}
			};
		}
		
		K getKey();
		
		V getValue();
		void set(V value) throws IOException;
	}
	
	long size();
	
	default boolean isEmpty(){
		return size()==0;
	}
	
	/**
	 * Provides an entry that provides the key and value in this map. The entry may be modifiable. If it is its modification will reflect in the contents of the map.
	 *
	 * @return null if the entry with specified key does not exist.
	 */
	@Nullable
	Entry<K, V> getEntry(K key) throws IOException;
	
	/**
	 * @param key key whose existance should be checked
	 * @return true if map contains the key
	 */
	default boolean containsKey(K key) throws IOException{
		return getEntry(key)!=null;
	}
	
	/**
	 * Provides a stream of read only entries
	 */
	@Override
	Stream<Entry<K, V>> stream();
	
	/**
	 * Provides an iterator of read only entries
	 */
	@Override
	default Iterator<Entry<K, V>> iterator(){
		return stream().iterator();
	}
	
	/**
	 * Retrieves a value that matches the provided key
	 */
	default V get(K key) throws IOException{
		var e=getEntry(key);
		if(e==null) return null;
		return e.getValue();
	}
	
	/**
	 * Adds a new entry to the map or overrides a value of an existing entry of the same key if it exists
	 *
	 * @param key   the key of the entry
	 * @param value the value of the entry
	 */
	void put(K key, V value) throws IOException;
	/**
	 * Puts all entries from the provided map
	 *
	 * @param values the map to put the entries from
	 */
	void putAll(Map<K, V> values) throws IOException;
	
	/**
	 * @param key the key of the entry to be removed
	 * @return true if the entry existed and was removed
	 */
	boolean remove(K key) throws IOException;
	
	static <K, V> String toString(IOMap<K, V> map){
		if(map.isEmpty()) return "{}";
		var i=map.iterator();
		
		StringBuilder sb=new StringBuilder();
		sb.append('{');
		long count=0;
		while(true){
			var e=i.next();
			count++;
			
			K key  =e.getKey();
			V value=e.getValue();
			
			sb.append(Utils.toShortString(key));
			sb.append('=');
			sb.append(Utils.toShortString(value));
			if(!i.hasNext()) return sb.append('}').toString();
			if(sb.length()>300){
				return sb.append(" ... ").append(map.size()-count).append("more }").toString();
			}
			sb.append(',').append(' ');
		}
	}
}
