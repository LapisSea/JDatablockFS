package com.lapissea.cfs.utils.function;

import java.util.function.Function;

public interface FunctionOI<T> extends Function<T, Integer>{
	@Override
	default Integer apply(T t){ return applyVal(t); }
	int applyVal(T t);
}
