package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.io.IOException;
import java.util.List;

public class ContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> long sizeOfUnknown(T instance, WordSpace wordSpace){
		return ContiguousStructPipe.of(instance.getThisStruct()).getSizeDescriptor().calcUnknown(instance, wordSpace);
	}
	
	public static <T extends IOInstance<T>> StructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> StructPipe<T> of(Struct<T> struct){
		return of(ContiguousStructPipe.class, struct);
	}
	
	public ContiguousStructPipe(Struct<T> type){
		super(type);
	}
	
	@Override
	protected List<IOField<T, ?>> initFields(){
		return IOFieldTools.stepFinal(getType().getFields(), List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		try{
			pushPool(ioPool);
			writeIOFields(provider, dest, instance);
		}finally{
			popPool();
		}
	}
	
	@Override
	protected T doRead(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var ioPool=makeIOPool();
		try{
			pushPool(ioPool);
			readIOFields(provider, src, instance, genericContext);
		}finally{
			popPool();
		}
		return instance;
	}
}
