package com.lapissea.cfs;

import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION;
	public static       boolean PRINT_COMPILATION;
	
	static{
		DEBUG_VALIDATION=testAssertion();
		
		PRINT_COMPILATION=UtilL.sysPropertyByClass(GlobalConfig.class, "printCompilation", false, Boolean::valueOf);
		
		if(DEBUG_VALIDATION){
			LogUtil.println("Running with debugging");
		}
	}
	
	private static boolean testAssertion(){
		try{
			assert false;
			return false;
		}catch(AssertionError e){
			return true;
		}
	}
	
}
