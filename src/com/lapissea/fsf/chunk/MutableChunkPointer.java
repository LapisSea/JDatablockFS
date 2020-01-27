package com.lapissea.fsf.chunk;

public class MutableChunkPointer extends ChunkPointer{
	public MutableChunkPointer(){
	}
	
	public MutableChunkPointer(Chunk chunk){
		super(chunk);
	}
	
	public MutableChunkPointer(long value){
		super(value);
	}
	
	
	public void set(Chunk chunk){
		value=chunk.getOffset();
	}
	
	public void setValue(long value){
		this.value=value;
	}
}
