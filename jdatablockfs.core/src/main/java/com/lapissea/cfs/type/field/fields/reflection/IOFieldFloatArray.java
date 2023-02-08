package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.OptionalLong;

public class IOFieldFloatArray<T extends IOInstance<T>> extends IOField<T, float[]>{
	
	private IOFieldPrimitive.FInt<T> arraySize;
	
	
	public IOFieldFloatArray(FieldAccessor<T> accessor){
		super(accessor);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(0, OptionalLong.empty(), (ioPool, prov, inst) -> {
			var siz = arraySize.getValue(ioPool, inst);
			if(siz>0) return siz*4L;
			var arr = get(ioPool, inst);
			return arr.length*4L;
		}));
	}
	@Override
	public void init(){
		super.init();
		arraySize = declaringStruct().getFields().requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr = get(ioPool, instance);
		dest.writeFloats4(arr);
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		var data = src.readFloats4(size);
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		src.skipExact(size*4L);
	}
}
