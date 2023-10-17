package com.lapissea.dfs.type;

public interface NewObj<T>{
	interface Instance<T extends IOInstance<T>> extends NewObj<T>{ }
	T make();
}
