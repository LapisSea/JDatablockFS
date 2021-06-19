package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;
import java.util.OptionalLong;

public class IOFieldByteArray<T extends IOInstance<T>> extends IOField<T, byte[]>{
	
	private final SizeDescriptor<T>   descriptor;
	private       IOField<T, Integer> arraySize;
	
	
	public IOFieldByteArray(IFieldAccessor<T> accessor){
		super(accessor);
		
		descriptor=new SizeDescriptor.Unknown<>(0, OptionalLong.empty()){
			@Override
			public long calcUnknown(T instance){
				var val=get(instance);
				return val.length;
			}
		};
	}
	@Override
	public void init(){
		super.init();
		arraySize=getStruct().getFields().exact(Integer.class, IOFieldTools.makeArrayLenName(getAccessor())).orElseThrow();
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public void write(ContentWriter dest, T instance) throws IOException{
		dest.writeInts1(get(instance));
	}
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
		byte[] data=new byte[arraySize.get(instance)];
		src.readFully(data);
		set(instance, data);
	}
}
