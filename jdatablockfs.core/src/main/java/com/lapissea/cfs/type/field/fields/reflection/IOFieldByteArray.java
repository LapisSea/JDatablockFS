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
import com.lapissea.cfs.type.field.annotations.IOCompression;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;

public class IOFieldByteArray<T extends IOInstance<T>> extends IOField<T, byte[]>{
	
	private final IOCompression.Type compression;
	private       IOField<T, byte[]> compressed;
	
	private final SizeDescriptor<T>        descriptor;
	private       IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldByteArray(FieldAccessor<T> accessor){
		super(accessor);
		
		compression=accessor.getAnnotation(IOCompression.class).map(IOCompression::value).orElse(null);
		
		descriptor=SizeDescriptor.Unknown.of(0, OptionalLong.empty(), (ioPool, prov, inst)->{
			if(compression!=null){
				return 0;
			}
			var siz=arraySize.getValue(ioPool, inst);
			if(siz>0) return siz;
			var arr=get(ioPool, inst);
			return arr.length;
		});
	}
	
	@Override
	public void init(){
		super.init();
		var fields=declaringStruct().getFields();
		arraySize=fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
		if(compression!=null){
			compressed=fields.requireExact(byte[].class, IOFieldTools.makePackName(getAccessor()));
		}
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		if(compression==null){
			return null;
		}
		return List.of(new ValueGeneratorInfo<>(compressed, new ValueGenerator<T, byte[]>(){
			@Override
			public boolean shouldGenerate(Struct.Pool<T> ioPool, DataProvider provider, T instance) throws IOException{
				byte[] compr=compressed.get(ioPool, instance);
				return compr==null;
			}
			@Override
			public byte[] generate(Struct.Pool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
				byte[] raw=get(ioPool, instance);
				return compression.pack(raw);
			}
		}));
	}
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	@Override
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr=get(ioPool, instance);
		if(compression!=null){
			return;
		}
		dest.writeInts1(arr);
	}
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(compression!=null){
			var data=compression.unpack(compressed.get(ioPool, instance));
			set(ioPool, instance, data);
			return;
		}
		
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
