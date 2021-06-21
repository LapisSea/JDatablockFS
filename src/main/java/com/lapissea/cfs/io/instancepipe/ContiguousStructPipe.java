package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.io.IOException;
import java.util.List;

public class ContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
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
		return IOFieldTools.stepFinal((List<IOField<T, ?>>)getType().getFields(), List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	@Override
	protected void doWrite(ContentWriter dest, T instance) throws IOException{
		writeIOFields(dest, instance);
	}
	
	@Override
	protected T doRead(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
		return instance;
	}
}
