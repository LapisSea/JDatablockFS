package com.lapissea.cfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOD<T> extends BiConsumer<T, Double>{
	@Override
	default void accept(T t, Double val){ acceptVal(t, val); }
	void acceptVal(T t, double val);
}
