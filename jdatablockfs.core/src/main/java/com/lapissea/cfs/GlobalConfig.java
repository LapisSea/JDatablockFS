package com.lapissea.cfs;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static com.lapissea.cfs.logging.Log.info;
import static com.lapissea.util.PoolOwnThread.async;

public class GlobalConfig{
	
	private static final String CONFIG_PROPERTY_PREFIX="dfs.";
	
	public static final boolean DEBUG_VALIDATION;
	
	public static final boolean RELEASE_MODE;
	public static final boolean TYPE_VALIDATION;
	public static       boolean PRINT_COMPILATION;
	
	static{
		DEBUG_VALIDATION=GlobalConfig.class.desiredAssertionStatus();
		RELEASE_MODE=configFlag("releaseMode", Objects.toString(GlobalConfig.class.getResource(GlobalConfig.class.getSimpleName()+".class")).startsWith("jar:"));
		
		TYPE_VALIDATION=configFlag("typeValidation", DEBUG_VALIDATION);
		PRINT_COMPILATION=configFlag("printCompilation", false);
		
		if(DEBUG_VALIDATION) async(()->info(
			"""
				Running with debugging:
					RELEASE_MODE: {}
					TYPE_VALIDATION: {}
					PRINT_COMPILATION: {}
				""",
			RELEASE_MODE, TYPE_VALIDATION, PRINT_COMPILATION
		));
	}
	
	public static Optional<String> configProp(String name){
		return Utils.optionalProperty(CONFIG_PROPERTY_PREFIX+name);
	}
	
	public static boolean configFlag(String name, boolean defaultValue){
		return configProp(name).map(Boolean::valueOf).orElse(defaultValue);
	}
	public static int configInt(String name, int defaultValue){
		return configProp(name).map(Integer::valueOf).orElse(defaultValue);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> T configEnum(String name, T defaultValue){
		Objects.requireNonNull(defaultValue);
		return configProp(name).map(s->Arrays.stream(defaultValue.getClass().getEnumConstants())
		                                     .map(e->(T)e)
		                                     .filter(e->e.name().equalsIgnoreCase(s))
		                                     .findAny())
		                       .filter(Optional::isPresent)
		                       .map(Optional::get)
		                       .orElse(defaultValue);
	}
	
}
