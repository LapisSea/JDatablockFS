package com.lapissea.cfs.objects;

public interface Wrapper<Wrapped>{
	static Object fullyUnwrappObj(Object obj){
		Object result = obj;
		while(result instanceof Wrapper<?> w){
			result = w.getWrappedObj();
		}
		return result;
	}
	
	Wrapped getWrappedObj();
}
