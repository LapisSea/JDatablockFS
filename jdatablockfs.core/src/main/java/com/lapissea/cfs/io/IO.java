package com.lapissea.cfs.io;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
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
