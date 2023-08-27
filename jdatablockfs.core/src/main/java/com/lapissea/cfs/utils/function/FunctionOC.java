package com.lapissea.cfs.utils.function;

import java.util.function.Function;

public interface FunctionOC<T> extends Function<T, Character>{
	@Override
	default Character apply(T t){ return applyVal(t); }
	char applyVal(T t);
}
