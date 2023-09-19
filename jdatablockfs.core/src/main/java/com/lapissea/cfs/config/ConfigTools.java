package com.lapissea.cfs.config;

import com.lapissea.cfs.logging.Log;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.util.ConsoleColors.*;

public final class ConfigTools{
	
	static final class Dummy implements ConfigDefs{ }
	
	public sealed interface DefaultValue<T>{
		record OtherFlagFallback<T>(Flag<T> flag) implements DefaultValue<T>{
			public OtherFlagFallback{ Objects.requireNonNull(flag); }
			@Override
			public T value(){ return flag.defaultValue().value(); }
		}
		
		record Literal<T>(T value) implements DefaultValue<T>{
			public Literal{ Objects.requireNonNull(value); }
		}
		
		record Lambda<T>(Supplier<T> valueMaker) implements DefaultValue<T>{
			public Lambda{ Objects.requireNonNull(valueMaker); }
			@Override
			public T value(){ return valueMaker.get(); }
		}
		
		T value();
	}
	
	/**
	 * Instructions on how to aqquire a flag by its name and now to fall back on to its default value
	 */
	public sealed interface Flag<T>{
		
		record FBool(String name, DefaultValue<Boolean> defaultValue) implements Flag<Boolean>{
			public FBool{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			
			/**
			 * @see FBool#resolveVal()
			 */
			@Override
			@Deprecated
			public Boolean resolve(){ return resolveVal(); }
			public boolean resolveVal(){
				return ConfigUtils.configBoolean(name, defaultValue.value());
			}
			
			public <U> Supplier<U> boolMap(U ifTrue, U ifFalse){
				return () -> resolveVal()? ifTrue : ifFalse;
			}
		}
		
		record FInt(String name, DefaultValue<Integer> defaultValue, IntFunction<String> validate) implements Flag<Integer>{
			public FInt{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			@Override
			public void set(Integer val){
				if(validate != null){
					var err = validate.apply(val);
					if(err != null){
						throw new IllegalArgumentException(name + " = " + val + " Reason: " + err);
					}
				}
				Flag.super.set(val);
			}
			
			/**
			 * @see FInt#resolveVal()
			 */
			@Override
			@Deprecated
			public Integer resolve(){ return resolveVal(); }
			public int resolveVal(){
				int def = defaultValue.value();
				int val = ConfigUtils.configInt(name, def);
				if(validate != null){
					var err = validate.apply(val);
					if(err != null){
						Log.warn("\"{}\" is not a valid value for {}. Reason: {}", val, name, err);
						return def;
					}
				}
				return val;
			}
			public FInt natural(){
				return withValidation(val -> {
					if(val<=0) return "Value must be greater than 0!";
					return null;
				});
			}
			public FInt positiveOptional(){
				return withValidation(val -> {
					if(val<0 && val != -1) return "Value must be positive or -1!";
					return null;
				});
			}
			public FInt positive(){
				return withValidation(val -> {
					if(val<0) return "Value must be positive!";
					return null;
				});
			}
			public FInt withValidation(IntFunction<String> validate){
				if(this.validate != null){
					var oldValidate = this.validate;
					var newValidate = validate;
					validate = val -> {
						var err = oldValidate.apply(val);
						if(err != null) return err;
						return newValidate.apply(val);
					};
				}
				return new FInt(name, defaultValue, validate);
			}
		}
		
		record FStr(String name, DefaultValue<String> defaultValue) implements Flag<String>{
			public FStr{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			
			@Override
			public String resolve(){
				return ConfigUtils.optionalProperty(name).orElseGet(defaultValue::value);
			}
		}
		
		record FStrOptional(String name) implements Flag<Optional<String>>{
			
			private static final DefaultValue<Optional<String>> DEFAULT_VALUE = new DefaultValue.Literal<>(Optional.empty());
			
			public FStrOptional{
				Objects.requireNonNull(name);
			}
			
			@Override
			public DefaultValue<Optional<String>> defaultValue(){
				return DEFAULT_VALUE;
			}
			
			@Override
			public Optional<String> resolve(){
				return ConfigUtils.optionalProperty(name);
			}
		}
		
		record FEnum<E extends Enum<E>>(String name, DefaultValue<E> defaultValue) implements Flag<E>{
			public FEnum{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			
			@Override
			public E resolve(){
				return ConfigUtils.configEnum(name, defaultValue.value());
			}
		}
		
		DefaultValue<T> defaultValue();
		String name();
		T resolve();
		
		default void set(T val){
			System.setProperty(name(), Objects.toString(val));
		}
		
		default <U> Supplier<U> map(Function<T, U> mapper){
			return () -> mapper.apply(resolve());
		}
	}
	
	public static Flag.FInt flagInt(String name, DefaultValue<Integer> defaultVal)  { return new Flag.FInt(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal, null); }
	public static Flag.FInt flagI(String name, Flag.FInt defaultVal)                { return flagInt(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FInt flagI(String name, int defaultVal)                      { return flagInt(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FInt flagI(String name, Supplier<Integer> valueMaker)        { return flagInt(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	public static Flag.FBool flagBool(String name, DefaultValue<Boolean> defaultVal){ return new Flag.FBool(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static Flag.FBool flagB(String name, Flag.FBool defaultVal)              { return flagBool(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FBool flagB(String name, boolean defaultVal)                 { return flagBool(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FBool flagB(String name, Supplier<Boolean> valueMaker)       { return flagBool(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	public static Flag.FStr flagStr(String name, DefaultValue<String> defaultVal)   { return new Flag.FStr(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static Flag.FStr flagS(String name, Flag.FStr defaultVal)                { return flagStr(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FStr flagS(String name, String defaultVal)                   { return flagStr(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FStr flagS(String name, Supplier<String> valueMaker)         { return flagStr(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	public static Flag.FStrOptional flagS(String name)                              { return new Flag.FStrOptional(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name)); }
	
	///
	
	public static <T extends Enum<T>> Flag.FEnum<T> flagEnum(String name, DefaultValue<T> defaultVal){ return new Flag.FEnum<>(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static <T extends Enum<T>> Flag.FEnum<T> flagE(String name, Flag.FEnum<T> defaultVal)     { return flagEnum(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.FEnum<T> flagEV(String name, T defaultVal)                { return flagEnum(name, new DefaultValue.Literal<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.FEnum<T> flagE(String name, Supplier<T> valueMaker)       { return flagEnum(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	
	public record ConfEntry(String name, String val){
		public static ConfEntry checked(String name, String val){
			if(!name.startsWith(ConfigDefs.CONFIG_PROPERTY_PREFIX)){
				throw new IllegalArgumentException(name + " does not start with " + ConfigDefs.CONFIG_PROPERTY_PREFIX);
			}
			return new ConfEntry(name, val);
		}
		public ConfEntry{
			Objects.requireNonNull(name);
			Objects.requireNonNull(val);
		}
	}
	
	public static String configFlagsToTable(List<ConfEntry> values, int padding, boolean grouping){
		var padStr = " ".repeat(padding);
		
		var nameLen = values.stream().map(ConfigTools.ConfEntry::name).mapToInt(String::length).max().orElse(0);
		
		var singles = new ArrayList<ConfEntry>();
		var groupsE = new ArrayList<Map.Entry<String, List<ConfEntry>>>();
		if(grouping){
			var groups = values.stream().collect(Collectors.groupingBy(e -> e.name.split("\\.")[1]));
			for(var e : groups.entrySet()){
				if(e.getValue().size() == 1){
					singles.add(e.getValue().get(0));
				}else{
					groupsE.add(e);
				}
			}
		}else{
			singles.addAll(values);
		}
		singles.sort(Comparator.comparing(e -> e.name));
		groupsE.sort(Map.Entry.comparingByKey());
		
		
		StringBuilder sb = new StringBuilder();
		for(var e : singles){
			var name = e.name;
			var val  = e.val;
			sb.append(padStr);
			var dotPos = name.indexOf('.');
			if(dotPos == -1) sb.append(name);
			else{
				sb.append(BLACK_BRIGHT).append(name, 0, dotPos + 1).append(RESET)
				  .append(name, dotPos + 1, name.length());
			}
			sb.append(" ".repeat(nameLen - name.length())).append(": ")
			  .append(val).append('\n');
		}
		
		for(var group : groupsE){
			var gName    = group.getKey();
			var elements = group.getValue();
			int pad      = nameLen - gName.length() - 2, before = pad/2, after = pad - before;
			sb.append(padStr).append("-".repeat(before)).append(' ')
			  .append(gName.length()>2? TextUtil.firstToUpperCase(gName) : gName).append(' ')
			  .append("-".repeat(after)).append('\n');
			
			var len = Arrays.stream(elements.get(0).name.split("\\.")).limit(2).mapToInt(s -> s.length() + 1).sum();
			for(var e : elements){
				var name     = e.name;
				var val      = e.val;
				var segments = name.split("\\.");
				sb.append(padStr);
				if(segments.length == 2){
					sb.append(name);
				}else{
					sb.append(BLACK_BRIGHT).append(name, 0, len).append(RESET).append(name, len, name.length());
				}
				sb.append(" ".repeat(nameLen - name.length())).append(": ")
				  .append(val).append('\n');
			}
		}
		
		return sb.toString();
	}
	
	public static List<ConfEntry> collectConfigFlags(){
		return configFlagFields().map(val -> {
			var name = val.name();
			return ConfEntry.checked(name, switch(val){
				case Flag.FEnum<?> enumFlag -> {
					var enums   = enumFlag.defaultValue().value().getClass().getEnumConstants();
					var enumStr = Arrays.stream(enums).map(Enum::toString).collect(Collectors.joining(", ", "[", "]"));
					yield PURPLE_BRIGHT + val.resolve() + RESET + " - " + PURPLE + enumStr + RESET;
				}
				case Flag.FBool bool -> BLUE + bool.resolve() + RESET;
				case Flag.FInt anInt -> YELLOW_BRIGHT + anInt.resolve() + RESET;
				case Flag.FStr str -> PURPLE_BRIGHT + str.resolve() + RESET;
				case Flag.FStrOptional str -> str.resolve().map(v -> PURPLE + v + RESET).orElse("");
			});
		}).toList();
	}
	public static Stream<Flag<?>> configFlagFields(){
		return Arrays.stream(ConfigDefs.class.getDeclaredFields()).filter(field -> UtilL.instanceOf(field.getType(), ConfigTools.Flag.class)).map(field -> {
			try{
				var obj = (Flag<?>)field.get(null);
				if(obj == null){
					throw new NullPointerException(field + " is null");
				}
				return obj;
			}catch(IllegalAccessException e){
				throw new RuntimeException(e);
			}
		});
	}
	
}
