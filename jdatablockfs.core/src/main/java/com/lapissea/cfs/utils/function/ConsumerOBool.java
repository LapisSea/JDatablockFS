package com.lapissea.cfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOBool<T> extends BiConsumer<T, Boolean>{
	@Override
	default void accept(T t, Boolean val){ acceptVal(t, val); }
	void acceptVal(T t, boolean val);
}
