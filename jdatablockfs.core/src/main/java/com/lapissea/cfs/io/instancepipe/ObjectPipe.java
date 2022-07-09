package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.BasicSizeDescriptor;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;

public interface ObjectPipe<T, PoolType>{
	
	void write(DataProvider provider, ContentWriter dest, T instance) throws IOException;
	T read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException;
	BasicSizeDescriptor<T, PoolType> getSizeDescriptor();
	PoolType makeIOPool();
	
	default void write(DataProvider.Holder holder, ContentWriter dest, T instance) throws IOException{
		write(holder.getDataProvider(), dest, instance);
	}
	default <Prov extends DataProvider.Holder&RandomIO.Creator> void write(Prov dest, T instance) throws IOException{
		try(var io=dest.io()){
			write(dest.getDataProvider(), io, instance);
		}
	}
	
	default <Prov extends DataProvider.Holder&RandomIO.Creator> T readNew(Prov src, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return readNew(src.getDataProvider(), io, genericContext);
		}
	}
	
	default T readNew(DataProvider provider, RandomIO.Creator src, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return readNew(provider, io, genericContext);
		}
	}
	
	
	default <Prov extends DataProvider.Holder&RandomIO.Creator> T read(Prov src, T instance, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return read(src.getDataProvider(), io, instance, genericContext);
		}
	}
	
	default <Prov extends DataProvider.Holder&RandomIO.Creator> void modify(Prov src, UnsafeConsumer<T, IOException> modifier, GenericContext genericContext) throws IOException{
		T val=readNew(src, genericContext);
		modifier.accept(val);
		write(src, val);
	}
	
	default long calcUnknownSize(DataProvider provider, T instance, WordSpace wordSpace){
		return getSizeDescriptor().calcUnknown(makeIOPool(), provider, instance, wordSpace);
	}
}