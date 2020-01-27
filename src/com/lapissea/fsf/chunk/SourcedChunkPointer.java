package com.lapissea.fsf.chunk;

public class SourcedChunkPointer{
	public final long         source;
	public final ChunkPointer pointer;
	
	public SourcedChunkPointer(ChunkPointer pointer, long source){
		this.pointer=pointer;
		this.source=source;
	}
}
