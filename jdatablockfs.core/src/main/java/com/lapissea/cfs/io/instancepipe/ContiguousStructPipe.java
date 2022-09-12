package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

public class ContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> long sizeOfUnknown(DataProvider provider, T instance, WordSpace wordSpace){
		var pip=ContiguousStructPipe.of(instance.getThisStruct());
		return pip.calcUnknownSize(provider, instance, wordSpace);
	}
	
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Class<T> type, int minRequestedStage){
		return of(Struct.of(type), minRequestedStage);
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Struct<T> struct){
		return of(ContiguousStructPipe.class, struct);
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Struct<T> struct, int minRequestedStage){
		return of(ContiguousStructPipe.class, struct, minRequestedStage);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends ContiguousStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)ContiguousStructPipe.class, newType);
	}
	
	public ContiguousStructPipe(Struct<T> type, boolean runNow){
		super(type, runNow);
	}
	
	@Override
	protected List<IOField<T, ?>> initFields(){
		return IOFieldTools.stepFinal(getType().getFields(), List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
}
