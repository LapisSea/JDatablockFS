package com.lapissea.cfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOL<T> extends BiConsumer<T, Long>{
	@Override
	default void accept(T t, Long val){ acceptVal(t, val); }
	void acceptVal(T t, long val);
}
