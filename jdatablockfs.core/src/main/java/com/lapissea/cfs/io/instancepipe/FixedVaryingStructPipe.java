package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VaryingSize;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

public class FixedVaryingStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	public static <T extends IOInstance<T>> BaseFixedStructPipe<T> tryVarying(Struct<T> type, VaryingSize.Provider rule){
		if(rule==VaryingSize.Provider.ALL_MAX){
			return FixedStructPipe.of(type, STATE_IO_FIELD);
		}
		try{
			return new FixedVaryingStructPipe<>(type, rule);
		}catch(UseFixed e){
			return FixedStructPipe.of(type, STATE_IO_FIELD);
		}
	}
	
	public static final class UseFixed extends Exception{}
	
	public FixedVaryingStructPipe(Struct<T> type, VaryingSize.Provider rule) throws UseFixed{
		super(type, (t, structFields)->{
			if(rule==VaryingSize.Provider.ALL_MAX){
				throw new UseFixed();
			}
			Set<IOField<T, ?>> sizeFields=sizeFieldStream(structFields).collect(Collectors.toSet());
			
			boolean[] effectivelyAllMax={true};
			var snitchRule=VaryingSize.Provider.intercept(rule, (max, actual)->{
				if(actual.size!=max) effectivelyAllMax[0]=false;
			});
			
			var result=fixedFields(t, structFields, sizeFields::contains, f->{
				return f.forceMaxAsFixedSize(snitchRule);
			});
			if(effectivelyAllMax[0]){
				throw new UseFixed();
			}
			return result;
		}, true);
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
