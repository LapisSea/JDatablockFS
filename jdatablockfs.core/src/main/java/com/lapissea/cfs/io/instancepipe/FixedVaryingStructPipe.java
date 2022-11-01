package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;

import java.io.IOException;
import java.util.stream.Collectors;

public class FixedVaryingStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	public FixedVaryingStructPipe(Struct<T> type, boolean initNow, IOField.VaryingSizeProvider rule){
		super(type, (t, structFields)->{
			var sizeFields=sizeFieldStream(structFields).collect(Collectors.toSet());
			return fixedFields(t, structFields, sizeFields::contains, f->{
				return f.forceMaxAsFixedSize(rule);
			});
		}, initNow);
		if(type instanceof Struct.Unmanaged){
			throw new IllegalArgumentException("Unmanaged types are not supported");
		}
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
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		T instance=getType().make();
		read(provider, src, instance, genericContext);
	}
}
