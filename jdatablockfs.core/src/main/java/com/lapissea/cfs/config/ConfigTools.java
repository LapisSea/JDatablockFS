package com.lapissea.cfs.config;

import com.lapissea.cfs.logging.Log;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

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
				return ConfigUtils.configBoolean(name, ConfigUtils.optionalProperty(name), defaultValue.value());
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
			public Integer resolve(){ return resolveVal(); }
			public int resolveVal(){
				int def = defaultValue.value();
				int val = ConfigUtils.configInt(name, ConfigUtils.optionalProperty(name), def);
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
		
		record Abc<E extends Enum<E>>(String name, DefaultValue<E> defaultValue) implements Flag<E>{
			public Abc{
				Objects.requireNonNull(name);
				Objects.requireNonNull(defaultValue);
			}
			
			@Override
			public E resolve(){
				return ConfigUtils.configEnum(name, ConfigUtils.optionalProperty(name), defaultValue.value());
			}
		}
		
		DefaultValue<T> defaultValue();
		String name();
		T resolve();
		
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
	
	///
	
	public static <T extends Enum<T>> Flag.Abc<T> flagEnum(String name, DefaultValue<T> defaultVal){ return new Flag.Abc<>(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static <T extends Enum<T>> Flag.Abc<T> flagE(String name, Flag.Abc<T> defaultVal)       { return flagEnum(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.Abc<T> flagE(String name, T defaultVal)                 { return flagEnum(name, new DefaultValue.Literal<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.Abc<T> flagE(String name, Supplier<T> valueMaker)       { return flagEnum(name, new DefaultValue.Lambda<>(valueMaker)); }
	public static <T extends Enum<T>> Flag.Abc<T> flagEDyn(String name, Supplier<T> valueMaker)    { return flagE(name, valueMaker); }
	
}
