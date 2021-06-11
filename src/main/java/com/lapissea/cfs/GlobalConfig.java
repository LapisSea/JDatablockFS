package com.lapissea.cfs;

import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION;
	public static       boolean PRINT_COMPILATION;
	
	static{
		boolean assertEnabled=false;
		
		try{
			assert false;
		}catch(AssertionError e){
			assertEnabled=true;
		}
		
		DEBUG_VALIDATION=assertEnabled;
		
		PRINT_COMPILATION=UtilL.sysPropertyByClass(GlobalConfig.class, "printCompilation", true, Boolean::valueOf);
		
		if(DEBUG_VALIDATION){
			LogUtil.println("Running with debugging");
		}
	}
	
}
