package com.lapissea.dfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOByt<T> extends BiConsumer<T, Byte>{
	@Override
	default void accept(T t, Byte val){ acceptVal(t, val); }
	void acceptVal(T t, byte val);
}
