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
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

public class FixedVaryingStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	private final Map<IOField<T, NumberSize>, NumberSize>                              fixed;
	private final Map<IOField<T, NumberSize>, UnsafeSupplier<NumberSize, IOException>> config;
	
	public FixedVaryingStructPipe(Struct<T> type, boolean initNow, Map<IOField<T, NumberSize>, UnsafeSupplier<NumberSize, IOException>> config){
		super(type, (t, structFields)->{
			var sizeFields=sizeFieldStream(structFields).collect(Collectors.toSet());
			sizeFields.removeAll(config.keySet());
			return fixedFieldsSet(t, structFields, sizeFields);
		}, initNow);
		if(!(type instanceof Struct.Unmanaged)){
			throw new IllegalArgumentException("Unmanaged types are not supported");
		}
		
		this.config=Map.copyOf(config);
		
		var max=computeMaxValues(getType().getFields());
		max.keySet().removeAll(this.config.keySet());
		fixed=Utils.nullIfEmpty(max);
	}
	
	private void setMax(T instance, VarPool<T> ioPool) throws IOException{
		if(fixed!=null){
			fixed.forEach((k, v)->k.set(ioPool, instance, v));
		}
		for(var entry : config.entrySet()){
			var field =entry.getKey();
			var getter=entry.getValue();
			
			field.set(ioPool, instance, getter.get());
		}
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		setMax(instance, ioPool);
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		setMax(instance, ioPool);
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		src.skipExact(calcSkipSipSize());
	}
	private long calcSkipSipSize(){
		throw new NotImplementedException();
	}
}
