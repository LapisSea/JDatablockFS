package com.lapissea.fsf.chunk;

import com.lapissea.fsf.INumber;

public class MutableChunkPointer extends ChunkPointer implements INumber.Mutable{
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
	
	@Override
	public void setValue(long value){
		this.value=value;
	}
}
