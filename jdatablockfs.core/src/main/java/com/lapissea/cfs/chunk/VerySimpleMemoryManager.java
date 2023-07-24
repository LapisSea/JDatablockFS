package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class VerySimpleMemoryManager extends MemoryManager.StrategyImpl{
	
	private final IOList<ChunkPointer> freeChunks = IOList.wrap(new ArrayList<>());
	private       boolean              defragmentMode;
	
	private final MoveInfo moveInfo = new MoveInfo(){
		@Override
		public void start(ChunkChainIO chain){ }
		@Override
		public void end(ChunkChainIO chain){ }
	};
	
	public VerySimpleMemoryManager(DataProvider context){
		super(context);
	}
	
	@Override
	protected List<AllocStrategy> createAllocs(){
		return List.of(
			(context1, ticket) -> {
				if(defragmentMode) return null;
				return MemoryOperations.allocateReuseFreeChunk(context1, ticket, true);
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
	public MoveInfo getMoveInfo(){
		return moveInfo;
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
}
