package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedObject;
import com.lapissea.dfs.objects.ChunkPointer;

import java.io.IOException;

public sealed interface PhysicalExtent permits Chunk, FreeChunk{
	
	ChunkPointer getPtr();
	
	long totalSize();
	int getHeaderSize();
	long dataEnd();
	
	static PhysicalExtent read(DataProvider provider, ChunkPointer ptr) throws IOException{
		try(var io = provider.getSource().ioAt(ptr.getValue())){
			if(FreeChunk.isFreeTag(io.readUnsignedInt1())){
				return FreeChunk.read(provider, ptr);
			}
		}
		
		var  chunk = provider.getChunk(ptr);
		long total = chunk.totalSize();
		
		if(total<Chunk.minSafeSize() || ptr.getValue()>Long.MAX_VALUE - total || chunk.dataEnd()>provider.getSource().getIOSize()){
			throw new MalformedObject("Invalid physical Chunk extent at " + ptr);
		}
		
		return chunk;
	}
}
