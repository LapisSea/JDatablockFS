package com.lapissea.cfs.config;

import java.util.Objects;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION = GlobalConfig.class.desiredAssertionStatus();
	
	public static final boolean RELEASE_MODE      = ConfigDefs.RELEASE_MODE.resolve();
	public static final boolean TYPE_VALIDATION   = ConfigDefs.TYPE_VALIDATION.resolve();
	public static       boolean PRINT_COMPILATION = ConfigDefs.PRINT_COMPILATION.resolve();
	public static final int     BATCH_BYTES       = ConfigDefs.BATCH_BYTES.resolve();
	
	public static String propName(String name){
		if(name.startsWith(ConfigDefs.CONFIG_PROPERTY_PREFIX)) return name;
		return ConfigDefs.CONFIG_PROPERTY_PREFIX + name;
	}
	
	public static boolean configFlag(String name, boolean defaultValue){
		Objects.requireNonNull(name);
		return ConfigUtils.configBoolean(propName(name), ConfigUtils.optionalProperty(propName(name)), defaultValue);
	}
	public static int configInt(String name, int defaultValue){
		Objects.requireNonNull(name);
		return ConfigUtils.configInt(propName(name), ConfigUtils.optionalProperty(propName(name)), defaultValue);
	}
	
	public static <T extends Enum<T>> T configEnum(String name, T defaultValue){
		Objects.requireNonNull(name);
		Objects.requireNonNull(defaultValue);
		return ConfigUtils.configEnum(propName(name), ConfigUtils.optionalProperty(propName(name)), defaultValue);
	}
}