package com.lapissea.dfs.utils.function;

import java.util.function.Function;

public interface FunctionOF<T> extends Function<T, Float>{
	@Override
	default Float apply(T t){ return applyVal(t); }
	float applyVal(T t);
}
