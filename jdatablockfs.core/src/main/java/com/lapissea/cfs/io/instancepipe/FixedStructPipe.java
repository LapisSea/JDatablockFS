package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class FixedStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	public static <T extends IOInstance<T>> PipeFieldCompiler<T> compiler(){
		return (t, structFields)->{
			var sizeFields=sizeFieldStream(structFields).collect(Collectors.toSet());
			return fixedFieldsSet(t, structFields, sizeFields);
		};
	}
	
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Class<T> type, int minRequestedStage){
		return of(Struct.of(type), minRequestedStage);
	}
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Struct<T> struct){
		return of(FixedStructPipe.class, struct);
	}
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Struct<T> struct, int minRequestedStage){
		return of(FixedStructPipe.class, struct, minRequestedStage);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends FixedStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)FixedStructPipe.class, newType);
	}
	
	private final Map<IOField<T, NumberSize>, NumberSize> maxValues;
	
	public FixedStructPipe(Struct<T> type, boolean initNow){
		this(type, compiler(), initNow);
	}
	public FixedStructPipe(Struct<T> type, PipeFieldCompiler<T> compiler, boolean initNow){
		super(type, compiler, initNow);
		
		maxValues=Utils.nullIfEmpty(computeMaxValues(getType().getFields()));
		
		if(DEBUG_VALIDATION){
			if(!(type instanceof Struct.Unmanaged)){
				if(!getSizeDescriptor().hasFixed()) throw new RuntimeException();
			}
		}
	}
	
	public <E extends IOInstance<E>> SizeDescriptor.Fixed<E> getFixedDescriptor(){
		return (SizeDescriptor.Fixed<E>)super.getSizeDescriptor();
	}
	
	private void setMax(T instance, VarPool<T> ioPool){
		maxValues.forEach((k, v)->k.set(ioPool, instance, v));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		if(maxValues!=null) setMax(instance, ioPool);
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(maxValues!=null) setMax(instance, ioPool);
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		src.skipExact(getFixedDescriptor().get());
	}
	
	public Map<IOField<T, NumberSize>, NumberSize> getMaxValues(){
		return maxValues;
	}
}
