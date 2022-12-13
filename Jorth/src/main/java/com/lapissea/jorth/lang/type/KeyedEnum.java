package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.util.UtilL;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface KeyedEnum{
	
	record Lookup<E extends Enum<E>>(Map<String, E> names, char[] smolKeys, E[] smolValues){
		
		private static final ConcurrentHashMap<Class<? extends Enum<?>>, Lookup<?>> CACHE = new ConcurrentHashMap<>();
		
		private static <E extends Enum<E>> Lookup<E> make(Class<E> t){
			var values = t.getEnumConstants();
			var names  = HashMap.<String, E>newHashMap(values.length);
			int count  = 0;
			for(var value : values){
				String key = value instanceof KeyedEnum e? e.key() : value.name().toLowerCase();
				if(key.length() == 1) count++;
				else names.put(key, value);
			}
			
			var smolKeys   = new char[count];
			var smolValues = UtilL.array(t, count);
			int i          = 0;
			for(var value : values){
				String key = value instanceof KeyedEnum e? e.key() : value.name().toLowerCase();
				if(key.length() != 1) continue;
				smolKeys[i] = key.charAt(0);
				smolValues[i] = value;
				i++;
			}
			
			return new Lookup<>(names, smolKeys, smolValues);
		}
		
		private MalformedJorth fail(String name){
			return new MalformedJorth(
				"Expected any of " +
				Stream.concat(
					      IntStream.range(0, smolKeys.length).mapToObj(i -> smolKeys[i] + ""),
					      names.keySet().stream()
				      ).sorted()
				      .collect(Collectors.joining(", ", "[", "]")) +
				" but got " + name);
		}
		
		public E getOptional(char key){
			return byChar(key);
		}
		
		public E get(char key) throws MalformedJorth{ return get(key, true); }
		public E get(char key, boolean fail) throws MalformedJorth{
			var e = byChar(key);
			if(e != null) return e;
			
			if(fail) throw fail(Character.toString(key));
			else return null;
		}
		public E getOptional(String key){
			return byStr(key);
		}
		
		public E get(String key) throws MalformedJorth{ return get(key, true); }
		public E get(String key, boolean fail) throws MalformedJorth{
			var e = byStr(key);
			if(e != null) return e;
			
			if(fail) throw fail(key);
			else return null;
		}
		
		private E byStr(String key){
			var e = names.get(key);
			if(e != null) return e;
			
			if(key.length() == 1){
				return byChar(key.charAt(0));
			}
			return null;
		}
		
		private E byChar(char key){
			var k = smolKeys;
			for(int i = 0; i<k.length; i++){
				if(k[i] == key){
					return smolValues[i];
				}
			}
			return null;
		}
	}
	
	static <E extends Enum<E>> E get(Class<E> clazz, String key) throws MalformedJorth{
		return getLookup(clazz).get(key, true);
	}
	static <E extends Enum<E>> E get(Class<E> clazz, char key) throws MalformedJorth{
		return getLookup(clazz).get(key, true);
	}
	
	static <E extends Enum<E>> E getOptional(Class<E> clazz, String key){
		return getLookup(clazz).getOptional(key);
	}
	static <E extends Enum<E>> E getOptional(Class<E> clazz, char key){
		return getLookup(clazz).getOptional(key);
	}
	
	static <E extends Enum<E>> Lookup<E> getLookup(Class<E> clazz){
		@SuppressWarnings("unchecked")
		var lookup = (Lookup<E>)Lookup.CACHE.get(clazz);
		if(lookup == null) Lookup.CACHE.put(clazz, lookup = Lookup.make(clazz));
		return lookup;
	}
	
	String key();
	
}
