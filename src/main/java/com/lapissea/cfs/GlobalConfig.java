package com.lapissea.cfs;

import com.lapissea.util.LogUtil;

public class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION;
//	public static final boolean DETAILED_WALK_REPORT;
	
	static{
		boolean assertEnabled=false;
		
		try{
			assert false;
		}catch(AssertionError e){
			assertEnabled=true;
		}
		
		DEBUG_VALIDATION=assertEnabled;

//		DETAILED_WALK_REPORT=UtilL.sysPropertyByClass(GlobalConfig.class, "DETAILED_WALK_REPORT", false, Boolean::valueOf);
		
		if(DEBUG_VALIDATION){
			LogUtil.println("Running with debugging");
		}
	}
	
}
