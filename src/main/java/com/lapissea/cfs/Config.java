package com.lapissea.cfs;

import com.lapissea.util.UtilL;

public class Config{
	
	public static final boolean DEBUG_VALIDATION;
	public static final boolean DETAILED_WALK_REPORT;
	
	static{
		boolean assertEnabled=false;
		
		try{
			assert false;
		}catch(AssertionError e){
			assertEnabled=true;
		}
		
		DEBUG_VALIDATION=assertEnabled;
		
		DETAILED_WALK_REPORT=UtilL.sysPropertyByClass(Config.class, "DETAILED_WALK_REPORT", false, Boolean::valueOf);
	}
	
}
