package com.lapissea.cfs.utils.function;

import java.util.function.Function;

public interface FunctionOByt<T> extends Function<T, Byte>{
	@Override
	default Byte apply(T t){ return applyVal(t); }
	byte applyVal(T t);
}
