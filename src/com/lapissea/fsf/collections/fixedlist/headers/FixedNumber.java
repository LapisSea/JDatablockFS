package com.lapissea.fsf.collections.fixedlist.headers;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.chunk.SizedChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.IOException;
import java.util.List;

public class FixedNumber extends FileObject.FullLayout<FixedNumber> implements FixedLenList.ElementHead<FixedNumber, ChunkPointer>{
	
	
	private static final SequenceLayout<FixedNumber> LAYOUT=FileObject.sequenceBuilder(List.of());
	
	private final NumberSize size;
	
	public FixedNumber(NumberSize size){
		super(LAYOUT);
		this.size=size;
	}
	
	@Override
	public FixedNumber copy(){
		return new FixedNumber(size);
	}
	
	@Override
	public boolean willChange(ChunkPointer element){
		return false;
	}
	
	@Override
	public void update(ChunkPointer element){ }
	
	@Override
	public int getElementSize(){
		return size.bytes;
	}
	@Override
	public ChunkPointer newElement(){
		return new SizedChunkPointer(size);
	}
	
	@Override
	public void readElement(ContentInputStream src, ChunkPointer dest) throws IOException{
		dest.read(src);
	}
	
	@Override
	public void writeElement(ContentOutputStream dest, ChunkPointer src) throws IOException{
		size.write(dest, src.getValue());
	}
	
	@Override
	public String toString(){
		return size+"S";
	}
}
