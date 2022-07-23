package com.lapissea.cfs;

import com.lapissea.util.UtilL;

import java.util.Objects;

import static com.lapissea.cfs.logging.Log.info;
import static com.lapissea.util.PoolOwnThread.async;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION;
	
	public static final boolean RELEASE_MODE;
	public static final boolean TYPE_VALIDATION;
	public static       boolean PRINT_COMPILATION;
	
	static{
		DEBUG_VALIDATION=GlobalConfig.class.desiredAssertionStatus();
		RELEASE_MODE=UtilL.sysPropertyByClass(GlobalConfig.class, "RELEASE_MODE").map(Boolean::valueOf)
		                  .orElseGet(()->Objects.toString(GlobalConfig.class.getResource(GlobalConfig.class.getSimpleName()+".class")).startsWith("jar:"));
		
		TYPE_VALIDATION=UtilL.sysPropertyByClass(GlobalConfig.class, "TYPE_VALIDATION", DEBUG_VALIDATION, Boolean::valueOf);
		PRINT_COMPILATION=UtilL.sysPropertyByClass(GlobalConfig.class, "printCompilation", false, Boolean::valueOf);
		
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
	
}
