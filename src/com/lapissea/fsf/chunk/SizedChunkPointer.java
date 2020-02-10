package com.lapissea.fsf.chunk;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.io.ContentReader;
import com.lapissea.fsf.io.ContentWriter;

import java.io.IOException;

public class SizedChunkPointer extends ChunkPointer{
	
	private NumberSize size;
	
	public SizedChunkPointer(NumberSize size){
		this(size, 0);
	}
	
	public SizedChunkPointer(NumberSize size, Chunk chunk){
		this(size, chunk.getOffset());
	}
	
	public SizedChunkPointer(NumberSize size, long value){
		super(value);
		this.size=size;
	}
	
	@Override
	public void read(ContentReader dest) throws IOException{
		value=size.read(dest);
	}
	
	@Override
	public void write(ContentWriter dest) throws IOException{
		size.write(dest, value);
	}
	
	@Override
	public long length(){
		return size.bytes;
	}
	
	public NumberSize getSize(){
		return size;
	}
	
	public void setSize(NumberSize size){
		this.size=size;
	}
	
	public SizedChunkPointer copy(){
		return new SizedChunkPointer(size, value);
	}
}
