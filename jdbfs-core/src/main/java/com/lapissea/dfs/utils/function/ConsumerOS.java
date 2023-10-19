package com.lapissea.dfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOS<T> extends BiConsumer<T, Short>{
	@Override
	default void accept(T t, Short val){ acceptVal(t, val); }
	void acceptVal(T t, short val);
}
