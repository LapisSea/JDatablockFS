package com.lapissea.cfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOC<T> extends BiConsumer<T, Character>{
	@Override
	default void accept(T t, Character val){ acceptVal(t, val); }
	void acceptVal(T t, char val);
}
