package com.lapissea.cfs.config;

import com.lapissea.cfs.logging.Log;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class ConfigUtils{
	
	public record FuzzyResult<T extends Enum<T>>(T value, String incorrect){
		public FuzzyResult(T value){
			this(value, null);
		}
		
		public T warn(String nameOfBadEnum){
			if(incorrect != null){
				Log.warn("{} can only be one of {} but is actually \"{}\". Defaulting to {}",
				         nameOfBadEnum,
				         value.getClass().getEnumConstants(),
				         incorrect,
				         value
				);
			}
			return value;
		}
	}
	
	public static int configInt(String name, Map<String, ?> map, int defaultValue){
		return configInt(name, Optional.ofNullable(map.get(name)).map(Object::toString), defaultValue);
	}
	public static int configInt(String name, int defaultValue){
		return configInt(name, optionalProperty(name), defaultValue);
	}
	public static int configInt(String name, Optional<String> value, int defaultValue){
		return value.map(s -> {
			if(s.endsWith(".0")) s = s.substring(0, s.length() - 2);
			try{
				return Integer.parseInt(s);
			}catch(NumberFormatException e){
				Log.warn("{} can only be an integer but is \"{}\"", name, s);
				return null;
			}
		}).orElse(defaultValue);
	}
	
	public static <T extends Enum<T>> T configEnum(String name, Map<String, ?> map, T defaultValue){
		return configEnum(name, Optional.ofNullable(map.get(name)).map(Object::toString), defaultValue);
	}
	public static <T extends Enum<T>> T configEnum(String name, Optional<String> value, T defaultValue){
		return configEnum(value, defaultValue).warn(name);
	}
	public static <T extends Enum<T>> T configEnum(String name, T defaultValue){
		return configEnum(name, optionalProperty(name), defaultValue);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> FuzzyResult<T> configEnum(Optional<String> value, T defaultValue){
		Objects.requireNonNull(defaultValue);
		return value.map(
			s -> Arrays.stream(defaultValue.getClass().getEnumConstants())
			           .filter(e -> e.name().equalsIgnoreCase(s))
			           .findAny()
			           .map(e -> new FuzzyResult<>((T)e))
			           .orElse(new FuzzyResult<>(defaultValue, s))
		).orElse(new FuzzyResult<>(defaultValue));
	}
	
	public static boolean configBoolean(String name, Map<String, ?> map, boolean defaultValue){
		return configBoolean(name, Optional.ofNullable(map.get(name)).map(Object::toString), defaultValue);
	}
	public static boolean configBoolean(String name, boolean defaultValue){
		return configBoolean(name, optionalProperty(name), defaultValue);
	}
	public static boolean configBoolean(String name, Optional<String> value, boolean defaultValue){
		return value.map(val -> switch(val.toLowerCase()){
			case "true", "yes" -> true;
			case "false", "no" -> false;
			default -> {
				Log.warn("{} can only be one of [true, false, yes, no] but is \"{}\"", name, val);
				yield false;
			}
		}).orElse(defaultValue);
	}
	
	
	public static Optional<String> optionalProperty(String name){
		return Optional.ofNullable(System.getProperty(name));
	}
	
	
}
