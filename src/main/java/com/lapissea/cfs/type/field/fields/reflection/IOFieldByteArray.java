package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.OptionalLong;

public class IOFieldByteArray<T extends IOInstance<T>> extends IOField<T, byte[]>{
	
	private final SizeDescriptor<T>        descriptor;
	private       IOFieldPrimitive.FInt<T> arraySize;
	
	
	public IOFieldByteArray(FieldAccessor<T> accessor){
		super(accessor);
		
		descriptor=new SizeDescriptor.Unknown<>(0, OptionalLong.empty(), (ioPool, prov, inst)->{
			var siz=arraySize.getValue(ioPool, inst);
			if(siz>0) return siz;
			var arr=get(ioPool, inst);
			return arr.length;
		});
	}
	@Override
	public void init(){
		super.init();
		arraySize=declaringStruct().getFields().requireExactInt(IOFieldTools.makeArrayLenName(getAccessor()));
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr=get(ioPool, instance);
		dest.writeInts1(arr);
	}
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int    size=arraySize.getValue(ioPool, instance);
		byte[] data=new byte[size];
		src.readFully(data);
		set(ioPool, instance, data);
	}
	
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size=arraySize.getValue(ioPool, instance);
		src.skipExact(size);
	}
}
