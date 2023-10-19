package com.lapissea.dfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOI<T> extends BiConsumer<T, Integer>{
	@Override
	default void accept(T t, Integer val){ acceptVal(t, val); }
	void acceptVal(T t, int val);
}
