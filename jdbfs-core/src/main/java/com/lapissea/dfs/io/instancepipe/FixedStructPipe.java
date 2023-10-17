package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.chunk.DataProvider;
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
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public class FixedStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	public static <T extends IOInstance<T>> PipeFieldCompiler<T, RuntimeException> compiler(){
		return (t, structFields) -> {
			Set<IOField<T, ?>> sizeFields = sizeFieldStream(structFields).collect(Collectors.toSet());
			return fixedFields(t, structFields, sizeFields::contains, IOField::forceMaxAsFixedSize);
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
	
	private Map<IOField<T, NumberSize>, NumberSize> maxValues;
	private boolean                                 maxValuesInited = false;
	
	public FixedStructPipe(Struct<T> type, boolean initNow){
		this(type, compiler(), initNow);
	}
	public FixedStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, boolean initNow){
		super(type, compiler, initNow);
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
