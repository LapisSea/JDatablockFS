package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.RandomIO;

import java.io.IOException;

public record Reference(ChunkPointer ptr, long offset){
	
	public RandomIO io(ChunkDataProvider provider) throws IOException{
		return ptr.dereference(provider).ioAt(offset);
	}
}
