package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.exceptions.MalformedJorth;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface KeyedEnum{
	
	final class Lookup<E extends Enum<E>>{
		
		private record CNode<E extends Enum<E>>(char key, E val, CNode<E> next){ }
		
		private static final ConcurrentHashMap<Class<? extends Enum<?>>, Lookup<?>> CACHE = new ConcurrentHashMap<>();
		
		private final Map<String, E> names;
		private final CNode<E>[]     smolNames;
		private final Class<E>       type;
		
		private Lookup(Class<E> type, Map<String, E> names, CNode<E>[] smolNames){
			this.names = names;
			this.smolNames = smolNames;
			this.type = type;
		}
		
		private static <E extends Enum<E>> Lookup<E> make(Class<E> t){
			var values = t.getEnumConstants();
			return make(t, Arrays.asList(values));
		}
		private static <E extends Enum<E>> Lookup<E> make(Class<E> t, Collection<E> values){
			var names = HashMap.<String, E>newHashMap(values.size());
			int count = 0;
			for(var value : values){
				if(value instanceof KeyedEnum e){
					String key = e.key();
					if(key.length() == 1) count++;
					else names.put(key, value);
				}else{
					String key = value.name();
					if(key.length() == 1) count += 2;
					else{
						names.put(key.toLowerCase(), value);
						names.put(key, value);
					}
				}
			}
			
			//noinspection unchecked
			var smolNames = (CNode<E>[])new CNode[count];
			
			for(var value : values){
				String key = value instanceof KeyedEnum e? e.key() : value.name().toLowerCase();
				if(key.length() != 1) continue;
				var c = key.charAt(0);
				int i = c%smolNames.length;
				smolNames[i] = new CNode<>(c, value, smolNames[i]);
			}
			
			return new Lookup<>(t, Map.copyOf(names), smolNames.length == 0? null : smolNames);
		}
		
		
		private String elsStr(String start, String end){
			Stream<String> stream;
			if(smolNames == null) stream = names.keySet().stream();
			else stream = Stream.concat(
				Arrays.stream(smolNames).mapMulti((n, c) -> {
					while(n != null){
						c.accept(n.key + "");
						n = n.next;
					}
				}),
				names.keySet().stream()
			);
			return stream.sorted().collect(Collectors.joining(", ", start, end));
		}
		private MalformedJorth fail(String name){
			return new MalformedJorth("Expected any of " + elsStr("[", "]") + " but got " + name);
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
			if(smolNames == null) return null;
			int i    = key%smolNames.length;
			var node = smolNames[i];
			if(node == null) return null;
			while(true){
				if(node.key == key) return node.val;
				if((node = node.next) == null) return null;
			}
		}
		
		@Override
		public boolean equals(Object obj){
			return obj == this ||
			       obj instanceof Lookup<?> that &&
			       this.type == that.type;
		}
		
		@Override
		public int hashCode(){
			return type.hashCode();
		}
		@Override
		public String toString(){
			return elsStr("Lookup{", "}");
		}
		
		public Lookup<E> excluding(E... es){
			if(es.length == 0) return this;
			
			var values = EnumSet.copyOf(names.values());
			if(smolNames != null){
				for(CNode<E> smolName : smolNames){
					while(smolName != null){
						values.add(smolName.val);
						smolName = smolName.next;
					}
				}
			}
			for(E e : es){
				values.remove(e);
			}
			return make(type, values);
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
	
	@SuppressWarnings("unchecked")
	static <E extends Enum<E>> Lookup<E> getLookup(Class<E> clazz){
		var lookup = (Lookup<E>)Lookup.CACHE.get(clazz);
		if(lookup == null) Lookup.CACHE.put(clazz, lookup = Lookup.make(clazz));
		return lookup;
	}
	
	String key();
	
}
