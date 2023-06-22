package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;

public final class IOFieldBooleanArray<T extends IOInstance<T>> extends IOField<T, boolean[]>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<boolean[]>{
		public Usage(){ super(boolean[].class); }
		@Override
		public <T extends IOInstance<T>> IOField<T, boolean[]> create(FieldAccessor<T> field, GenericContext genericContext){
			return new IOFieldBooleanArray<>(field);
		}
	}
	
	private IOFieldPrimitive.FInt<T> arraySize;
	
	public IOFieldBooleanArray(FieldAccessor<T> accessor){
		super(accessor);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var siz = arraySize.getValue(ioPool, inst);
			if(siz>0) return siz;
			var arr = get(ioPool, inst);
			return BitUtils.bitsToBytes(arr.length);
		}));
	}
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		arraySize = fields.requireExactInt(IOFieldTools.makeCollectionLenName(getAccessor()));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var arr = get(ioPool, instance);
		try(var b = new BitOutputStream(dest)){
			b.writeBits(arr);
		}
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int       size = arraySize.getValue(ioPool, instance);
		boolean[] data = new boolean[size];
		try(var b = new BitInputStream(src, size)){
			b.readBits(data);
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = arraySize.getValue(ioPool, instance);
		src.skipExact(size);
	}
}
