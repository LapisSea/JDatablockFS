package com.lapissea.cfs.config;

import com.lapissea.cfs.Utils;
import com.lapissea.util.LogUtil;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;

public class GlobalConfig{
	
	private static final String CONFIG_PROPERTY_PREFIX = "dfs.";
	
	public static final boolean DEBUG_VALIDATION = GlobalConfig.class.desiredAssertionStatus();
	
	public static final boolean RELEASE_MODE      = configFlag("releaseMode", isInJar());
	public static final boolean TYPE_VALIDATION   = configFlag("typeValidation", DEBUG_VALIDATION);
	public static       boolean PRINT_COMPILATION = configFlag("printCompilation", false);
	public static final int     BATCH_BYTES       = configInt("batchBytes", 1<<13);
	
	public static Optional<String> configProp(String name){
		return Utils.optionalProperty(CONFIG_PROPERTY_PREFIX + name);
	}
	
	public static String propName(String name){
		return CONFIG_PROPERTY_PREFIX + name;
	}
	
	public static boolean configFlag(String name, boolean defaultValue){
		return configProp(name).map(Boolean::valueOf).orElse(defaultValue);
	}
	public static int configInt(String name, int defaultValue){
		return configProp(name).map(Integer::valueOf).orElse(defaultValue);
	}
	
	public static <T extends Enum<T>> T configEnum(String name, T defaultValue){
		Objects.requireNonNull(defaultValue);
		return Utils.findFuzzyEnum(configProp(name), defaultValue)
		            .warn("Property " + CONFIG_PROPERTY_PREFIX + name);
	}
	
	private static boolean isInJar(){
		URL url = GlobalConfig.class.getResource(GlobalConfig.class.getSimpleName() + ".class");
		Objects.requireNonNull(url);
		var proto = url.getProtocol();
		return switch(proto){
			case "jar", "war" -> true;
			case "file" -> false;
			default -> {
				LogUtil.printlnEr("Warning:", proto, " is an unknown source protocol");
				yield false;
			}
		};
	}
}
