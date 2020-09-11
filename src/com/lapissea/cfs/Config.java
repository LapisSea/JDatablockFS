package com.lapissea.cfs;

public class Config{
	
	public static final boolean DEBUG_VALIDATION;
	
	static{
		boolean assertEnabled=false;
		
		try{
			assert false;
		}catch(AssertionError e){
			assertEnabled=true;
		}
		
		DEBUG_VALIDATION=assertEnabled;
	}
	
}
