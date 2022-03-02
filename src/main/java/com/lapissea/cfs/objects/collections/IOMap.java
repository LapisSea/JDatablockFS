package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public interface IOMap<K, V>{
	
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
	
	Entry<K, V> getEntry(K key) throws IOException;
	
	default boolean containsKey(K key) throws IOException{
		return getEntry(key)!=null;
	}
	
	Stream<Entry<K, V>> stream();
	
	default IterablePP<Entry<K, V>> entries(){
		return ()->stream().iterator();
	}
	
	default V get(K key) throws IOException{
		var e=getEntry(key);
		if(e==null) return null;
		return e.getValue();
	}
	
	void put(K key, V value) throws IOException;
	void putAll(Map<K, V> values) throws IOException;
	
	
	static <K, V> String toString(IOMap<K, V> map){
		if(map.isEmpty()) return "{}";
		var i=map.entries().iterator();
		
		StringBuilder sb=new StringBuilder();
		sb.append('{');
		while(true){
			var e=i.next();
			
			K key  =e.getKey();
			V value=e.getValue();
			
			sb.append(Utils.toShortString(key));
			sb.append('=');
			sb.append(Utils.toShortString(value));
			if(!i.hasNext()) return sb.append('}').toString();
			sb.append(',').append(' ');
		}
	}
}
