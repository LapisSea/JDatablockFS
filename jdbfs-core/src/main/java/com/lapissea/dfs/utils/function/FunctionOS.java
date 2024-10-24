package com.lapissea.dfs.utils.function;

import java.util.function.Function;

public interface FunctionOS<T> extends Function<T, Short>{
	@Override
	default Short apply(T t){ return applyVal(t); }
	short applyVal(T t);
}
