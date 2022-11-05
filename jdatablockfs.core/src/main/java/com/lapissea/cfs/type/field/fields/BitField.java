package com.lapissea.cfs.type.field.fields;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class BitField<T extends IOInstance<T>, Type> extends IOField<T, Type>{
	
	protected BitField(FieldAccessor<T> field){
		super(field);
	}
	
	private void checkWritten(VarPool<T> ioPool, DataProvider provider, T instance, BitOutputStream writer) throws IOException{
		writer.requireWritten(getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT));
	}
	private void checkRead(VarPool<T> ioPool, DataProvider provider, T instance, BitInputStream reader) throws IOException{
		reader.requireRead(getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT));
	}
	
	@Deprecated
	@Override
	public final void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		try(var writer=new BitOutputStream(dest)){
			writeBits(ioPool, writer, instance);
			if(DEBUG_VALIDATION) checkWritten(ioPool, provider, instance, writer);
		}
	}
	
	@Deprecated
	@Override
	public final void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try(var reader=new BitInputStream(src, getSizeDescriptor().getFixed(WordSpace.BIT).orElse(-1))){
			readBits(ioPool, reader, instance);
			if(DEBUG_VALIDATION) checkRead(ioPool, provider, instance, reader);
		}
	}
	
	@Deprecated
	@Override
	public final void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		try(var reader=new BitInputStream(src, -1)){
			skipReadBits(reader, instance);
			if(DEBUG_VALIDATION) checkRead(ioPool, provider, instance, reader);
		}
	}
	
	public abstract void writeBits(VarPool<T> ioPool, BitWriter<?> dest, T instance) throws IOException;
	public abstract void readBits(VarPool<T> ioPool, BitReader src, T instance) throws IOException;
	public abstract void skipReadBits(BitReader src, T instance) throws IOException;
	
	@Override
	public BitField<T, Type> implMaxAsFixedSize(){
		throw new NotImplementedException();
	}
}
