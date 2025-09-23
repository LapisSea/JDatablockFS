package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;

import java.io.IOException;
import java.util.Map;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public class FixedStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	public static <T extends IOInstance<T>> PipeFieldCompiler<T, RuntimeException> compiler(){
		return (t, structFields, testRun) -> {
			var sizeFields = sizeFieldStream(structFields).toModSet();
			var fields     = fixedFields(t, structFields, sizeFields::contains, IOField::forceMaxAsFixedSize);
			return new PipeFieldCompiler.Result<>(fields);
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
	
	private Map<IOField<T, NumberSize>, NumberSize> maxValues;
	private boolean                                 maxValuesInited = false;
	
	public FixedStructPipe(Struct<T> type, int syncStage){
		this(type, compiler(), syncStage);
	}
	public FixedStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, int syncStage){
		super(type, compiler, syncStage);
	}
	
	@Override
	public Class<StructPipe<T>> getSelfClass(){
		//noinspection unchecked,rawtypes
		return (Class)FixedStructPipe.class;
	}
	@Override
	protected void postValidate(){
		if(DEBUG_VALIDATION){
			if(!(getType() instanceof Struct.Unmanaged)){
				if(!getSizeDescriptor().hasFixed()){
					throw new RuntimeException("Unmanaged type not fixed");
				}
			}
		}
		super.postValidate();
	}
	
	private void setMax(T instance, VarPool<T> ioPool){
		maxValues.forEach((k, v) -> k.set(ioPool, instance, v));
	}
	private void initMax(){
		maxValuesInited = true;
		maxValues = Utils.nullIfEmpty(computeMaxValues(getType().getFields()));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		if(!maxValuesInited) initMax();
		if(maxValues != null) setMax(instance, ioPool);
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(!maxValuesInited) initMax();
		if(maxValues != null) setMax(instance, ioPool);
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
}
