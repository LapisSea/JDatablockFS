package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

public class VerySimpleMemoryManager extends MemoryManager.StrategyImpl{
	
	private static final boolean PURGE_ACCIDENTAL=true;
	
	private final IOList<ChunkPointer> freeChunks=IOList.wrap(new ArrayList<>(), ()->null);
	
	public VerySimpleMemoryManager(DataProvider context){
		super(context);
	}
	
	@Override
	protected List<AllocStrategy> createAllocs(){
		return List.of(
//			MemoryOperations::allocateReuseFreeChunk,
			MemoryOperations::allocateAppendToFile
		);
	}
	
	@Override
	protected List<AllocToStrategy> createAllocTos(){
		return List.of(
			(f, target, toAllocate)->MemoryOperations.growFileAlloc(target, toAllocate),
			(f, target, toAllocate)->MemoryOperations.allocateBySimpleNextAssign(this, target, toAllocate)
		);
	}
	
	@Override
	public IOList<ChunkPointer> getFreeChunks(){
		return freeChunks;
	}
	
	@Override
	public void free(Collection<Chunk> toFree) throws IOException{
		List<Chunk> toAdd=MemoryOperations.mergeChunks(toFree, PURGE_ACCIDENTAL);
		for(Chunk chunk : toAdd){
			var    r    =new Random(chunk.getPtr().getValue());
			byte[] noise=new byte[(int)chunk.getCapacity()];
			r.nextBytes(noise);
			context.getSource().write(chunk.dataStart(), false, noise);
		}
		MemoryOperations.mergeFreeChunksSorted(context, freeChunks, toAdd);
	}
}
