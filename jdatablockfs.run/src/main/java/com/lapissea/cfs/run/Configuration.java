package com.lapissea.cfs.run;

import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class Configuration{
	
	public interface Loader{
		Stream<Map.Entry<String, Object>> load();
		
		class DashedNameValueArgs implements Loader{
			
			private final String[] args;
			public DashedNameValueArgs(String[] args){
				this.args = args;
			}
			
			@Override
			public Stream<Map.Entry<String, Object>> load(){
				return Stream.generate(new Supplier<Map.Entry<String, Object>>(){
					int index;
					@Override
					public Map.Entry<String, Object> get(){
						if(index == args.length) return null;
						var name = args[index++];
						if(!name.startsWith("-")) throw new IllegalArgumentException(name + " is not a valid name, names start with -");
						name = name.substring(1);
						if(index == args.length) return new AbstractMap.SimpleImmutableEntry<>(name, null);
						
						var val = new StringBuilder(args[index++]);
						while(index<args.length && !args[index].startsWith("-")){
							val.append(" ").append(args[index++]);
						}
						var str = val.toString();
						try{
							return new AbstractMap.SimpleImmutableEntry<>(name, Integer.parseInt(str));
						}catch(NumberFormatException ignored){ }
						try{
							return new AbstractMap.SimpleImmutableEntry<>(name, Double.parseDouble(str));
						}catch(NumberFormatException ignored){ }
						return new AbstractMap.SimpleImmutableEntry<>(name, str);
					}
				}).takeWhile(Objects::nonNull);
			}
			
		}
		
		class JsonArgs implements Loader{
			
			private final File    source;
			private final boolean optional;
			public JsonArgs(File source, boolean optional){
				this.source = source;
				this.optional = optional;
			}
			
			@Override
			public Stream<Map.Entry<String, Object>> load(){
				Map<Object, Object> m;
				try(var reader = new FileReader(source)){
					m = new GsonBuilder().create().<HashMap<Object, Object>>fromJson(reader, HashMap.class);
				}catch(IOException e){
					if(optional) m = Map.of();
					else throw new RuntimeException(e);
				}
				
				return m.entrySet()
				        .stream()
				        .map(e -> new AbstractMap.SimpleImmutableEntry<>((String)e.getKey(), e.getValue()));
			}
		}
		
	}
	
	public class View{
		public int getInt(String name, int defaultValue){
			var val = arguments.get(name);
			return switch(val){
				case null -> defaultValue;
				case Integer num -> num;
				case Number num -> num.intValue();
				case String s -> Integer.parseInt(s);
				default -> throw new IllegalStateException(val + " can not be converted to int");
			};
		}
		public boolean getBoolean(String name, boolean defaultValue){
			var val = arguments.get(name);
			return switch(val){
				case null -> defaultValue;
				case Boolean b -> b;
				case String s -> Boolean.parseBoolean(s);
				default -> throw new IllegalStateException(val + " can not be converted to boolean");
			};
		}
	}
	
	private final Map<String, Object> arguments = new HashMap<>();
	private final View                view      = new View();
	
	public void load(Loader source){
		source.load().forEach(e -> arguments.put(e.getKey(), e.getValue()));
	}
	
	public View getView(){
		return view;
	}
}
