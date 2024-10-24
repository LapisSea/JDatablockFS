package com.lapissea.dfs.io;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.util.Nullable;

import java.io.IOException;

public interface IO<T extends IOInstance<T>>{
	
	@Nullable
	void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException;
	void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	
	interface DisabledIO<T extends IOInstance<T>> extends IO<T>{
		@Override
		default void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance){
			throw new UnsupportedOperationException();
		}
		@Override
		default void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext){
			throw new UnsupportedOperationException();
		}
		@Override
		default void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext){
			throw new UnsupportedOperationException();
		}
	}
}
