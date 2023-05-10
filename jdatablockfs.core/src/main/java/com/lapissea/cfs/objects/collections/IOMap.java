package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.IterablePP;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@IOValue.OverrideType.DefaultImpl(HashIOMap.class)
public interface IOMap<K, V> extends IterablePP<IOMap.IOEntry<K, V>>{
	
	interface IOEntry<K, V>{
		
		interface Modifiable<K, V> extends IOEntry<K, V>{
			abstract class Abstract<K, V> extends IOEntry.Abstract<K, V> implements Modifiable<K, V>{ }
			
			class Unsupported<K, V> extends Abstract<K, V>{
				
				private final K key;
				private final V value;
				
				public Unsupported(K key, V value){
					this.key = key;
					this.value = value;
				}
				
				@Override
				public void set(V value) throws IOException{
					throw new UnsupportedOperationException();
				}
				
				@Override
				public K getKey(){
					return key;
				}
				@Override
				public V getValue(){
					return value;
				}
			}
			
			void set(V value) throws IOException;
		}
		
		abstract class Abstract<K, V> implements IOEntry<K, V>, Stringify{
			@Override
			public String toString(){
				return this.getClass().getSimpleName() + "{" + Utils.toShortString(getKey()) + " = " + Utils.toShortString(getValue()) + "}";
			}
			@Override
			public String toShortString(){
				return "{" + Utils.toShortString(getKey()) + " = " + Utils.toShortString(getValue()) + "}";
			}
			@Override
			public boolean equals(Object obj){
				return obj == this ||
				       obj instanceof IOEntry<?, ?> e &&
				       Objects.equals(getKey(), e.getKey()) &&
				       Objects.equals(getValue(), e.getValue());
			}
			@Override
			public int hashCode(){
				var k = getKey();
				var v = getKey();
				
				int result = 1;
				result = 31*result + (k == null? 0 : k.hashCode());
				result = 31*result + (v == null? 0 : v.hashCode());
				return result;
			}
		}
		
		final class Simple<K, V> extends Abstract<K, V>{
			private final K key;
			private final V value;
			
			public Simple(K key, V value){
				this.key = key;
				this.value = value;
			}
			
			@Override
			public K getKey(){
				return key;
			}
			@Override
			public V getValue(){
				return value;
			}
		}
		
		
		static <K, V> IOEntry<K, V> of(K key, V value){
			return new Simple<>(key, value);
		}
		
		static <K, V> IOEntry.Modifiable<K, V> viewOf(Map.Entry<K, V> entry){
			class View extends Modifiable.Abstract<K, V>{
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
			}
			return new View();
		}
		
		K getKey();
		V getValue();
	}
	
	long size();
	
	default boolean isEmpty(){
		return size() == 0;
	}
	
	/**
	 * Provides an entry that provides the key and value in this map. The entry may be modifiable.
	 * If it is, its modification will reflect in the contents of the map.
	 *
	 * @return null if the entry with specified key does not exist.
	 */
	@Nullable
	IOEntry.Modifiable<K, V> getEntry(K key) throws IOException;
	
	/**
	 * @param key key whose existence should be checked
	 * @return true if the key is present in the map
	 */
	default boolean containsKey(K key) throws IOException{
		return getEntry(key) != null;
	}
	
	/**
	 * Provides a stream of read only entries
	 */
	@Override
	Stream<IOEntry<K, V>> stream();
	
	/**
	 * Provides an iterator of read only entries
	 */
	@Override
	default Iterator<IOEntry<K, V>> iterator(){
		return stream().iterator();
	}
	
	/**
	 * Retrieves a value that matches the provided key
	 */
	default V get(K key) throws IOException{
		var e = getEntry(key);
		if(e == null) return null;
		return e.getValue();
	}
	
	/**
	 * Adds a new key-value pair to the map. If the key already exists in the map,
	 * the function updates the value associated with that key to the new value provided.
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
		var i = map.iterator();
		
		StringBuilder sb = new StringBuilder();
		sb.append('{');
		long count = 0;
		while(true){
			var e = i.next();
			count++;
			
			K key   = e.getKey();
			V value = e.getValue();
			
			sb.append(Utils.toShortString(key));
			sb.append('=');
			sb.append(Utils.toShortString(value));
			if(!i.hasNext()) return sb.append('}').toString();
			if(sb.length()>300){
				return sb.append(" ... ").append(map.size() - count).append("more }").toString();
			}
			sb.append(',').append(' ');
		}
	}
}
