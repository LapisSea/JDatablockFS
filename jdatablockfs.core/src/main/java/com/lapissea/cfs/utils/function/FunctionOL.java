package com.lapissea.cfs.utils.function;

import java.util.function.Function;

public interface FunctionOL<T> extends Function<T, Long>{
	@Override
	default Long apply(T t){ return applyVal(t); }
	long applyVal(T t);
}
