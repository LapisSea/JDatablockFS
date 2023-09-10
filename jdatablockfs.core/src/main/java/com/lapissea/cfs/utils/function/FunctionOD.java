package com.lapissea.cfs.utils.function;

import java.util.function.Function;

public interface FunctionOD<T> extends Function<T, Double>{
	@Override
	default Double apply(T t){ return applyVal(t); }
	double applyVal(T t);
}
