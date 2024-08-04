package com.lapissea.dfs.config;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.exceptions.IllegalConfiguration;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ConfigUtils{
	
	public record FuzzyResult<T extends Enum<T>>(T value, String incorrect){
		public FuzzyResult(T value){
			this(value, null);
		}
		
		public T fail(String nameOfBadEnum){
			if(incorrect != null && (Log.WARN || ConfigDefs.STRICT_FLAGS.resolveVal())) logBad(nameOfBadEnum);
			return value;
		}
		
		private void logBad(String nameOfBadEnum){
			
			logBadValue(Log.fmt(
				"{} can only be one of {} but is actually \"{}\". Defaulting to {}",
				nameOfBadEnum,
				value.getClass().getEnumConstants(),
				incorrect,
				value
			));
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
				logBadInt(name, s);
				return null;
			}
		}).orElse(defaultValue);
	}
	
	private static void logBadInt(String name, String s){
		logBadValue(Log.fmt(
			"{} can only be an integer but is \"{}\"", name, s
		));
	}
	
	public static Duration configDuration(String name, Map<String, ?> map, Duration defaultValue){
		return configDuration(name, Optional.ofNullable(map.get(name)).map(Object::toString), defaultValue);
	}
	public static Duration configDuration(String name, Duration defaultValue){
		return configDuration(name, optionalProperty(name), defaultValue);
	}
	public static Duration configDuration(String name, Optional<String> value, Duration defaultValue){
		return value.map(s -> {
			try{
				var unitStart = 0;
				while(unitStart<s.length()){
					var c = s.charAt(unitStart);
					if(Character.isDigit(c) || c == '.' || c == '_' || c == '-') unitStart++;
					else break;
				}
				var numStr = s.substring(0, unitStart).replace("_", "");
				var num    = new BigDecimal(numStr);
				
				var unit = s.substring(unitStart).toUpperCase().trim();
				if(unit.isEmpty()) throw new RuntimeException("Unit of time must be present");
				if(unit.length()>3 && unit.charAt(unit.length() - 1) == 'S') unit = unit.substring(0, unit.length() - 1);
				//@formatter:off
				long toNanosMultiplier = switch(unit){
					case "H", "HOUR"          -> 60*60*1000*1000_000L;
					case "M", "MIN", "MINUTE" ->    60*1000*1000_000L;
					case "S", "SEC", "SECOND" ->       1000*1000_000L;
					case "MS", "MILLISECOND"  ->            1000_000L;
					case "NS", "NANOSECOND"   ->                   1L;
					default -> throw new RuntimeException("Unexpected unit: \"" + unit + '"');
				};
				//@formatter:on
				
				var nanos = num.multiply(BigDecimal.valueOf(toNanosMultiplier));
				
				return bigToDuration(nanos);
			}catch(Throwable e){
				logBadDuration(name, s, "\n\t" + e.getMessage());
				return null;
			}
		}).orElse(defaultValue);
	}
	
	private static Duration bigToDuration(BigDecimal val){
		var nanosPerSecond = BigInteger.valueOf(1000_000_000L);
		
		var integer = val.toBigInteger();
		var seconds = integer.divide(nanosPerSecond);
		var nanos   = integer.subtract(seconds.multiply(nanosPerSecond));
		
		return Duration.ofSeconds(seconds.longValueExact(), nanos.longValueExact());
	}
	
	private static void logBadDuration(String name, String s, String extra){
		logBadValue(Log.fmt(
			"{} can only be a duration. A duration is made of number + a unit of time like: h/hour, m/min... but is \"{}\"{}", name, s, extra
		));
	}
	
	public static <T extends Enum<T>> T configEnum(String name, Map<String, ?> map, T defaultValue){
		return configEnum(name, Optional.ofNullable(map.get(name)).map(Object::toString), defaultValue);
	}
	public static <T extends Enum<T>> T configEnum(String name, Optional<String> value, T defaultValue){
		return configEnum(value, defaultValue).fail(name);
	}
	public static <T extends Enum<T>> T configEnum(String name, T defaultValue){
		return configEnum(name, optionalProperty(name), defaultValue);
	}
	
	public static <T extends Enum<T>> FuzzyResult<T> configEnum(Optional<String> value, T defaultValue){
		Objects.requireNonNull(defaultValue);
		return value.map(
			s -> matchEnum(defaultValue, s)
				     .or(() -> matchEnum(defaultValue, Utils.camelCaseToSnakeCase(s)))
				     .map(FuzzyResult::new)
				     .orElse(new FuzzyResult<>(defaultValue, s))
		).orElse(new FuzzyResult<>(defaultValue));
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends Enum<T>> Optional<T> matchEnum(T defaultValue, String s){
		return Iters.from((T[])defaultValue.getClass().getEnumConstants())
		            .firstMatching(e -> {
			            if(e instanceof NamedEnum ne){
				            return Iters.from(ne.names()).anyMatch(s::equalsIgnoreCase);
			            }
			            return e.name().equalsIgnoreCase(s);
		            });
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
				if(Log.WARN || ConfigDefs.STRICT_FLAGS.resolveVal()) logBadBool(name, val);
				yield defaultValue;
			}
		}).orElse(defaultValue);
	}
	
	private static void logBadBool(String name, String val){
		logBadValue(Log.fmt(
			"{} can only be one of [true, false, yes, no] but is \"{}\"", name, val
		));
	}
	
	
	public static Optional<String> optionalProperty(String name){
		return Optional.ofNullable(System.getProperty(name));
	}
	
	
	private static void logBadValue(String msg){
		if(ConfigDefs.STRICT_FLAGS.resolveVal()){
			throw new IllegalConfiguration("\n" + msg);
		}
		Log.log(msg);
	}
	
}
