package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class PersistentMemoryManager extends MemoryManager.StrategyImpl{
	
	private final List<ChunkPointer> queuedFreeChunks=new ArrayList<>();
	
	private final IOList<ChunkPointer> freeChunks;
	private       boolean              defragmentMode;
	
	private boolean adding;
	
	public PersistentMemoryManager(DataProvider context, IOList<ChunkPointer> freeChunks){
		super(context);
		this.freeChunks=freeChunks;
	}
	
	@Override
	protected List<AllocStrategy> createAllocs(){
		return List.of(
			(context1, ticket)->{
				if(defragmentMode) return null;
				return MemoryOperations.allocateReuseFreeChunk(context1, ticket);
			},
			MemoryOperations::allocateAppendToFile
		);
	}
	
	@Override
	protected List<AllocToStrategy> createAllocTos(){
		return List.of(
			(f, target, toAllocate)->MemoryOperations.growFileAlloc(target, toAllocate),
			(f, target, toAllocate)->MemoryOperations.growFreeAlloc(this, target, toAllocate),
			(f, target, toAllocate)->MemoryOperations.allocateBySimpleNextAssign(this, target, toAllocate),
			(f, target, toAllocate)->MemoryOperations.allocateByGrowingHeaderNextAssign(this, target, toAllocate)
		);
	}
	
	@Override
	public DefragSes openDefragmentMode(){
		boolean oldDefrag=defragmentMode;
		defragmentMode=true;
		return ()->defragmentMode=oldDefrag;
	}
	@Override
	public IOList<ChunkPointer> getFreeChunks(){
		return adding?IOList.wrap(queuedFreeChunks, ()->null):freeChunks;
	}
	
	@Override
	public void free(Collection<Chunk> toFree) throws IOException{
		if(toFree.isEmpty()) return;
		
		var popped=popFile(toFree);
		if(popped.isEmpty()) return;
		
		List<Chunk> toAdd=MemoryOperations.mergeChunks(popped);
		
		if(adding){
			synchronized(queuedFreeChunks){
				toAdd.stream().sorted(Comparator.comparingLong(Chunk::getCapacity)).map(Chunk::getPtr).forEach(queuedFreeChunks::add);
			}
			return;
		}
		
		adding=true;
		try{
			synchronized(queuedFreeChunks){
				for(Chunk chunk : toAdd){
					queuedFreeChunks.add(chunk.getPtr());
				}
			}
			do{
				synchronized(queuedFreeChunks){
					var capacity       =freeChunks.getCapacity();
					var optimalCapacity=freeChunks.size()+queuedFreeChunks.size();
					if(capacity<optimalCapacity){
						var cap=optimalCapacity+1;
						freeChunks.requestCapacity(cap);
						assert freeChunks.getCapacity()==cap:freeChunks.getCapacity()+" "+cap;
					}
					var chs=new ArrayList<Chunk>(queuedFreeChunks.size());
					while(!queuedFreeChunks.isEmpty()){
						synchronized(queuedFreeChunks){
							if(queuedFreeChunks.isEmpty()) break;
							var ptr=queuedFreeChunks.remove(queuedFreeChunks.size()-1);
							var ch =context.getChunk(ptr);
							chs.add(ch);
						}
					}
					chs.sort(Comparator.naturalOrder());
					
					MemoryOperations.mergeFreeChunksSorted(context, freeChunks, chs);
				}
			}while(!queuedFreeChunks.isEmpty());
		}finally{
			adding=false;
		}
	}
	
	private Collection<Chunk> popFile(Collection<Chunk> toFree) throws IOException{
		Collection<Chunk> result=toFree;
		boolean           dirty =true;
		wh:
		while(true){
			for(var i=result.iterator();i.hasNext();){
				Chunk chunk=i.next();
				if(!chunk.checkLastPhysical()) continue;
				
				if(dirty){
					dirty=false;
					result=new ArrayList<>(result);
					continue wh;
				}
				var popCapacity=chunk.getPtr().getValue();
				var ptr        =chunk.getPtr();
				
				i.remove();
				
				for(Chunk c : result){
					if(c.getNextPtr().equals(ptr)){
						c.modifyAndSave(Chunk::clearNextPtr);
					}
				}
				try(var io=context.getSource().io()){
					io.setCapacity(popCapacity);
				}
				continue wh;
			}
			return result;
		}
	}
}
