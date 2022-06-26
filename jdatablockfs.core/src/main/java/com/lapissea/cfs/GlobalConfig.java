package com.lapissea.cfs;

import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION;
	public static       boolean PRINT_COMPILATION;
	
	static{
		DEBUG_VALIDATION=GlobalConfig.class.desiredAssertionStatus();
		
		PRINT_COMPILATION=UtilL.sysPropertyByClass(GlobalConfig.class, "printCompilation", false, Boolean::valueOf);
		
		if(DEBUG_VALIDATION){
			LogUtil.println("Running with debugging");
		}
	}
	
}
