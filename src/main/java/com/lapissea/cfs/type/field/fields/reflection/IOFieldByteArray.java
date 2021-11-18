package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;

public class IOFieldByteArray<T extends IOInstance<T>> extends IOField<T, byte[]>{
	
	private final SizeDescriptor<T>   descriptor;
	private       IOField<T, Integer> arraySize;
	
	
	public IOFieldByteArray(FieldAccessor<T> accessor){
		super(accessor);
		
		descriptor=new SizeDescriptor.Unknown<>(0, OptionalLong.empty(), inst->{
			var siz=arraySize.get(inst);
			if(siz>0) return siz;
			var arr=get(inst);
			return arr.length;
		});
	}
	@Override
	public void init(){
		super.init();
		arraySize=declaringStruct().getFields().requireExact(Integer.class, IOFieldTools.makeArrayLenName(getAccessor()));
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public List<IOField<T, ?>> write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr=get(instance);
		dest.writeInts1(arr);
		return List.of();
	}
	@Override
	public void read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int    size=arraySize.get(instance);
		byte[] data=new byte[size];
		src.readFully(data);
		set(instance, data);
	}
	
	@Override
	public void skipRead(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size=arraySize.get(instance);
		src.skipExact(size);
	}
}
