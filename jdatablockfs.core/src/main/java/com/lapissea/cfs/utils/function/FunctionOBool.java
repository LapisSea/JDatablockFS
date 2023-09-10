package com.lapissea.cfs.utils.function;

import java.util.function.Function;

public interface FunctionOBool<T> extends Function<T, Boolean>{
	@Override
	default Boolean apply(T t){ return applyVal(t); }
	boolean applyVal(T t);
}
