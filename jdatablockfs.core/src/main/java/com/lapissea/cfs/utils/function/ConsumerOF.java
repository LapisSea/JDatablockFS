package com.lapissea.cfs.utils.function;

import java.util.function.BiConsumer;

public interface ConsumerOF<T> extends BiConsumer<T, Float>{
	@Override
	default void accept(T t, Float val){ acceptVal(t, val); }
	void acceptVal(T t, float val);
}
