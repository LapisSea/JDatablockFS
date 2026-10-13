package com.lapissea.dfs.core.memory;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.MemoryManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.collections.IOList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class VerySimpleMemoryManager
	extends MemoryManager.StrategyImpl<VerySimpleMemoryManager.AllocStrategy, VerySimpleMemoryManager.AllocToStrategy>{
	
	protected enum AllocStrategy{
		REUSE_FREE_CHUNKS,
		APPEND_TO_FILE
	}
	
	protected enum AllocToStrategy{
		GROW_FILE_ALLOC,
		GROW_FREE_ALLOC,
		SIMPLE_NEXT_ASSIGN,
		CHAIN_WALK_UP_DEFRAGMENT,
		GROWING_HEADER_NEXT_ASSIGN,
	}
	
	private final IOList<ChunkPointer> freeChunks = IOList.wrap(new ArrayList<>());
	private       boolean              defragmentMode;
	
	public VerySimpleMemoryManager(DataProvider context){
		super(context, AllocStrategy.class.getEnumConstants(), AllocToStrategy.class.getEnumConstants());
	}
	
	@Override
	protected synchronized Chunk alloc(AllocStrategy strategy, DataProvider ctx, AllocateTicket ticket, boolean dryRun) throws IOException{
		return switch(strategy){
			case REUSE_FREE_CHUNKS -> {
				if(defragmentMode) yield null;
				yield MemoryOperations.allocateReuseFreeChunk(ctx, ticket, true, dryRun);
			}
			case APPEND_TO_FILE -> {
				yield MemoryOperations.allocateAppendToFile(ctx, ticket, dryRun);
			}
		};
	}
	@Override
	protected synchronized long allocTo(AllocToStrategy strategy, Chunk first, Chunk target, long toAllocate) throws IOException{
		return switch(strategy){
			case GROW_FILE_ALLOC -> MemoryOperations.growFileAlloc(target, toAllocate);
			case GROW_FREE_ALLOC -> MemoryOperations.growFreeAlloc(this, target, toAllocate, true);
			case SIMPLE_NEXT_ASSIGN -> MemoryOperations.allocateBySimpleNextAssign(this, first, target, toAllocate);
			case CHAIN_WALK_UP_DEFRAGMENT -> MemoryOperations.allocateByChainWalkUpDefragment(this, first, target, toAllocate);
			case GROWING_HEADER_NEXT_ASSIGN -> MemoryOperations.allocateByGrowingHeaderNextAssign(this, first, target, toAllocate);
		};
	}
	
	@Override
	public IOList<ChunkPointer> getFreeChunks(){
		context.checkValid();
		try{
			context.getUnmanagedRegistry().enlist(this, () -> {
				var snapshot = new ArrayList<ChunkPointer>();
				for(var pointer : freeChunks){
					snapshot.add(pointer);
				}
				return () -> {
					freeChunks.clear();
					freeChunks.addAll(snapshot);
				};
			});
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
		return freeChunks;
	}
	
	@Override
	public void free(Collection<Chunk> toFree) throws IOException{
		context.getSource().openIOTransaction(() -> {
			context.checkValid();
			if(toFree.isEmpty()) return;
			getFreeChunks(); // Capture the transient free list before its first mutation
			List<Chunk> toAdd = MemoryOperations.mergeChunks(toFree);
			MemoryOperations.mergeFreeChunksSorted(context, freeChunks, toAdd);
			for(var chunk : toFree) context.getUnmanagedRegistry().freed(chunk);
		});
	}
	
	@Override
	public void notifyStart(ChunkChainIO chain){ }
	@Override
	public void notifyEnd(ChunkChainIO chain){ }
	@Override
	public void refreshAfterRollback(){ }
}
