package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldUnmanagedObjectReference;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class FixedVaryingStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	private final Map<IOField<T, NumberSize>, UnsafeSupplier<NumberSize, IOException>> config;
	
	public FixedVaryingStructPipe(Struct<T> type, boolean initNow, Supplier<UnsafeSupplier<NumberSize, IOException>> numProvider){
		super(type, (t, structFields)->{
			return fixedFieldsSet(t, structFields, Set.of());
		}, initNow);
		if(type instanceof Struct.Unmanaged){
			throw new IllegalArgumentException("Unmanaged types are not supported");
		}
		
		Map<IOField<T, NumberSize>, UnsafeSupplier<NumberSize, IOException>> config=new HashMap<>();
		
		for(IOField<T, ?> field : type.getFields()){
			
			if(field instanceof IOFieldUnmanagedObjectReference<?, ?> unmanaged){
				LogUtil.println(field);
			}else{
				throw new NotImplementedException(field.getClass().getName());
			}
		}
		
		
		this.config=config;
	}
	
	private void setMax(T instance, VarPool<T> ioPool) throws IOException{
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
		T instance=getType().make();
		read(provider, src, instance, genericContext);
	}
	
	public Map<IOField<T, NumberSize>, UnsafeSupplier<NumberSize, IOException>> getConfig(){
		return config;
	}
}
