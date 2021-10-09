package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public interface IOMap<K, V>{
	
	interface Entry<K, V>{
		
		abstract class Abstract<K, V> implements Entry<K, V>{
			@Override
			public String toString(){
				return this.getClass().getSimpleName()+"{"+TextUtil.toShortString(getKey())+" = "+TextUtil.toShortString(getValue())+"}";
			}
			public String toShortString(){
				return "{"+TextUtil.toShortString(getKey())+" = "+TextUtil.toShortString(getValue())+"}";
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
	
	IterablePP<Entry<K, V>> entries();
	
	default V get(K key) throws IOException{
		var e=getEntry(key);
		if(e==null) return null;
		return e.getValue();
	}
	
	void put(K key, V value) throws IOException;
	
	
	static String toString(IOMap<?, ?> map){
		if(map.isEmpty()) return "{}";
		var i=map.entries().iterator();
		
		StringBuilder sb=new StringBuilder();
		sb.append('{');
		for(;;){
			var e=i.next();
			
			Object key=e.getKey();
			Object value;
			value=e.getValue();
			sb.append(TextUtil.toShortString(key));
			sb.append('=');
			sb.append(TextUtil.toShortString(value));
			if(!i.hasNext()) return sb.append('}').toString();
			sb.append(',').append(' ');
		}
	}
}
