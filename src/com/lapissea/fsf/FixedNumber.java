package com.lapissea.fsf;

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
		return new ChunkPointer();
	}
	
	@Override
	public void readElement(ContentInputStream src, ChunkPointer dest) throws IOException{
		dest.value=size.read(src);
	}
	
	@Override
	public void writeElement(ContentOutputStream dest, ChunkPointer src) throws IOException{
		size.write(dest, src.value);
	}
	
	@Override
	public String toString(){
		return size+"S";
	}
}
