package com.lapissea.cfs;

import com.lapissea.util.UtilL;

import static com.lapissea.cfs.logging.Log.info;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION;
	public static final boolean TYPE_VALIDATION;
	public static       boolean PRINT_COMPILATION;
	
	static{
		DEBUG_VALIDATION=GlobalConfig.class.desiredAssertionStatus();
		
		TYPE_VALIDATION=UtilL.sysPropertyByClass(GlobalConfig.class, "TYPE_VALIDATION", DEBUG_VALIDATION, Boolean::valueOf);
		PRINT_COMPILATION=UtilL.sysPropertyByClass(GlobalConfig.class, "printCompilation", false, Boolean::valueOf);
		
		if(DEBUG_VALIDATION){
			info(
				"""
					Running with debugging
					TYPE_VALIDATION: {}
					PRINT_COMPILATION: {}
					""",
				TYPE_VALIDATION, PRINT_COMPILATION
			);
		}
	}
	
}
