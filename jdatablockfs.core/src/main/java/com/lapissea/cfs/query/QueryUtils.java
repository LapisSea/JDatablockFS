package com.lapissea.cfs.query;

import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.util.NotImplementedException;

public enum QueryUtils{
	;
	
	public static Class<?> addTyp(Class<?> l, Class<?> r){
		var lt=SupportedPrimitive.get(l).orElseThrow();
		var rt=SupportedPrimitive.get(r).orElseThrow();
		if(lt.getType()!=rt.getType()) throw new NotImplementedException();
		return lt.getType();
	}
}
