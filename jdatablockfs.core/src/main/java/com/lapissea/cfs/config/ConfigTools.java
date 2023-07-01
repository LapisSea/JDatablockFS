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
		
		record Bool(String name, DefaultValue<Boolean> defaultValue) implements Flag<Boolean>{
			public Bool{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			@Override
			public Boolean resolve(){ return resolveVal(); }
			public boolean resolveVal(){
				return ConfigUtils.configBoolean(name, defaultValue.value());
			}
			
			public <U> Supplier<U> boolMap(U ifTrue, U ifFalse){
				return () -> resolveVal()? ifTrue : ifFalse;
			}
		}
		
		record Int(String name, DefaultValue<Integer> defaultValue, IntFunction<String> validate) implements Flag<Integer>{
			public Int{
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
			
			@Override
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
			public Int natural(){
				return withValidation(val -> {
					if(val<=0) return "Value must be greater than 0!";
					return null;
				});
			}
			public Int positiveOptional(){
				return withValidation(val -> {
					if(val<0 && val != -1) return "Value must be positive or -1!";
					return null;
				});
			}
			public Int positive(){
				return withValidation(val -> {
					if(val<0) return "Value must be positive!";
					return null;
				});
			}
			public Int withValidation(IntFunction<String> validate){
				if(this.validate != null){
					var oldValidate = this.validate;
					var newValidate = validate;
					validate = val -> {
						var err = oldValidate.apply(val);
						if(err != null) return err;
						return newValidate.apply(val);
					};
				}
				return new Int(name, defaultValue, validate);
			}
		}
		
		record Str(String name, DefaultValue<String> defaultValue) implements Flag<String>{
			public Str{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			
			@Override
			public String resolve(){
				return ConfigUtils.optionalProperty(name).orElseGet(defaultValue::value);
			}
		}
		
		record StrOptional(String name) implements Flag<Optional<String>>{
			
			private static final DefaultValue<Optional<String>> DEFAULT_VALUE = new DefaultValue.Literal<>(Optional.empty());
			
			public StrOptional{
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
		
		record Abc<E extends Enum<E>>(String name, DefaultValue<E> defaultValue) implements Flag<E>{
			public Abc{
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
	
	public static Flag.Int flagInt(String name, DefaultValue<Integer> defaultVal)  { return new Flag.Int(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal, null); }
	public static Flag.Int flagI(String name, Flag.Int defaultVal)                 { return flagInt(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.Int flagI(String name, int defaultVal)                      { return flagInt(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.Int flagI(String name, Supplier<Integer> valueMaker)        { return flagInt(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	public static Flag.Bool flagBool(String name, DefaultValue<Boolean> defaultVal){ return new Flag.Bool(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static Flag.Bool flagB(String name, Flag.Bool defaultVal)               { return flagBool(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.Bool flagB(String name, boolean defaultVal)                 { return flagBool(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.Bool flagB(String name, Supplier<Boolean> valueMaker)       { return flagBool(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	public static Flag.Str flagStr(String name, DefaultValue<String> defaultVal)   { return new Flag.Str(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static Flag.Str flagS(String name, Flag.Str defaultVal)                 { return flagStr(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.Str flagS(String name, String defaultVal)                   { return flagStr(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.Str flagS(String name, Supplier<String> valueMaker)         { return flagStr(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	public static Flag.StrOptional flagS(String name)                              { return new Flag.StrOptional(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name)); }
	
	///
	
	public static <T extends Enum<T>> Flag.Abc<T> flagEnum(String name, DefaultValue<T> defaultVal){ return new Flag.Abc<>(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static <T extends Enum<T>> Flag.Abc<T> flagE(String name, Flag.Abc<T> defaultVal)       { return flagEnum(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.Abc<T> flagEV(String name, T defaultVal)                { return flagEnum(name, new DefaultValue.Literal<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.Abc<T> flagE(String name, Supplier<T> valueMaker)       { return flagEnum(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	
	public record ConfEntry(String name, String val){
		public ConfEntry{
			if(!name.startsWith(ConfigDefs.CONFIG_PROPERTY_PREFIX)){
				throw new IllegalArgumentException(name + " does not start with " + ConfigDefs.CONFIG_PROPERTY_PREFIX);
			}
		}
	}
	
	public static String configFlagsToTable(List<ConfEntry> values, int padding){
		var padStr = " ".repeat(padding);
		
		var nameLen = values.stream().map(ConfigTools.ConfEntry::name).mapToInt(String::length).max().orElse(0);
		
		var groups = values.stream().collect(Collectors.groupingBy(e -> e.name.split("\\.")[1]));
		
		var singles = new ArrayList<ConfEntry>();
		var groupsE = new ArrayList<Map.Entry<String, List<ConfEntry>>>();
		for(var e : groups.entrySet()){
			if(e.getValue().size() == 1){
				singles.add(e.getValue().get(0));
			}else{
				groupsE.add(e);
			}
		}
		singles.sort(Comparator.comparing(e -> e.name));
		groupsE.sort(Map.Entry.comparingByKey());
		
		
		StringBuilder sb = new StringBuilder();
		for(ConfEntry(String name, String val) : singles){
			var segments = name.split("\\.");
			var len      = segments[0].length() + 1;
			sb.append(padStr);
			sb.append(BLACK_BRIGHT).append(name, 0, len).append(RESET).append(name, len, name.length());
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
			for(ConfEntry(String name, String val) : elements){
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
		List<ConfEntry> values = new ArrayList<>();
		try{
			for(var field : ConfigDefs.class.getDeclaredFields()){
				if(UtilL.instanceOf(field.getType(), ConfigTools.Flag.class)){
					var val  = (ConfigTools.Flag<?>)field.get(null);
					var name = val.name();
					values.add(new ConfEntry(name, switch(val){
						case ConfigTools.Flag.Abc<?> enumFlag -> {
							var enums   = enumFlag.defaultValue().value().getClass().getEnumConstants();
							var enumStr = Arrays.stream(enums).map(Enum::toString).collect(Collectors.joining(", ", "[", "]"));
							yield PURPLE_BRIGHT + val.resolve() + RESET + " - " + PURPLE + enumStr + RESET;
						}
						case ConfigTools.Flag.Bool bool -> BLUE + bool.resolve() + RESET;
						case ConfigTools.Flag.Int anInt -> YELLOW_BRIGHT + anInt.resolve() + RESET;
						case ConfigTools.Flag.Str str -> PURPLE_BRIGHT + str.resolve() + RESET;
						case ConfigTools.Flag.StrOptional str -> str.resolve().map(v -> PURPLE + v + RESET).orElse("");
					}));
				}
			}
			return values;
		}catch(IllegalAccessException e){
			throw new RuntimeException(e);
		}
		
	}
	
}
