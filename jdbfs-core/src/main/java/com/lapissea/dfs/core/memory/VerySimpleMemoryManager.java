package com.lapissea.dfs.core.memory;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.MemoryManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.collections.IOList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class VerySimpleMemoryManager extends MemoryManager.StrategyImpl{
	
	private final IOList<ChunkPointer> freeChunks = IOList.wrap(new ArrayList<>());
	private       boolean              defragmentMode;
	
	public VerySimpleMemoryManager(DataProvider context){
		super(context);
	}
	
	@Override
	protected List<AllocStrategy> createAllocs(){
		return List.of(
			(ctx, ticket, dryRun) -> {
				if(defragmentMode) return null;
				return MemoryOperations.allocateReuseFreeChunk(ctx, ticket, true, dryRun);
			},
			MemoryOperations::allocateAppendToFile
		);
	}
	
	@Override
	protected List<AllocToStrategy> createAllocTos(){
		return List.of(
			(first, target, toAllocate) -> MemoryOperations.growFileAlloc(target, toAllocate),
			(first, target, toAllocate) -> MemoryOperations.growFreeAlloc(this, target, toAllocate, true),
			(first, target, toAllocate) -> MemoryOperations.allocateBySimpleNextAssign(this, first, target, toAllocate),
			(first, target, toAllocate) -> MemoryOperations.allocateByChainWalkUpDefragment(this, first, target, toAllocate),
			(first, target, toAllocate) -> MemoryOperations.allocateByGrowingHeaderNextAssign(this, first, target, toAllocate)
		);
	}
	@Override
	public DefragSes openDefragmentMode(){
		boolean oldDefrag = defragmentMode;
		defragmentMode = true;
		return () -> defragmentMode = oldDefrag;
	}
	@Override
	public IOList<ChunkPointer> getFreeChunks(){
		return freeChunks;
	}
	
	@Override
	public void free(Collection<Chunk> toFree) throws IOException{
		if(toFree.isEmpty()) return;
		List<Chunk> toAdd = MemoryOperations.mergeChunks(toFree);
		MemoryOperations.mergeFreeChunksSorted(context, freeChunks, toAdd);
	}
	
	@Override
	public void notifyStart(ChunkChainIO chain){ }
	@Override
	public void notifyEnd(ChunkChainIO chain){ }
}
