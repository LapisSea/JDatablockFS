package com.lapissea.cfs.cluster;

import com.lapissea.cfs.conf.AllocateTicket;
import com.lapissea.cfs.exceptions.ActionStopException;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.PairM;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;

class Pack{
	
	private static void timeout(Instant end) throws ActionStopException{
		if(end!=null&&Instant.now().isAfter(end)){
			throw new ActionStopException();
		}
	}
	
	
	static void mergeChains(Cluster cluster, Instant end) throws IOException, ActionStopException{
		
		while(true){
			timeout(end);
			var opt=cluster.findPointer(e->cluster.getChunk(e).hasNext());
			if(opt.isEmpty()) break;
			Chunk root=cluster.getChunk(opt.get().headPtr());
			
			Chunk solid=AllocateTicket.bytes(root.chainCapacity()).submit(cluster);
			cluster.chainToChunk(root, solid);
			cluster.validate();
		}
	}
	
	private static boolean consumeFreeChunkStart(Cluster cluster, IOList<ChunkPointer> freeChunks, Chunk toMerge, int freeIndex) throws IOException{
		
		Chunk split=MAllocer.FREE_START_CONSUME.consumeFreeChunkStart(cluster, freeChunks, freeIndex,
		                                                              AllocateTicket.bytes(toMerge.getCapacity())
		                                                                            .shouldDisableResizing(toMerge.isNextDisabled()));
		if(split!=null){
			split.modifyAndSave(ch->ch.setIsUserData(toMerge.isUserData()));
			cluster.copyDataAndMoveChunk(toMerge, split);
		}
		return split!=null;
	}
	
	static void freeChunkScan(Cluster cluster, IOList<ChunkPointer> freeChunks, Instant end) throws IOException, ActionStopException{
		
		long    limitedPos=0;
		boolean lastFreed =false;
		while(!freeChunks.isEmpty()){
			timeout(end);
			
			ChunkPointer firstFreePtr=null;
			int          firstIndex  =-1;
			
			for(int i=0;i<freeChunks.size();i++){
				ChunkPointer ptr=freeChunks.getElement(i);
				if(ptr==null) continue;
				if(ptr.compareTo(limitedPos)<0) continue;
				
				if(firstFreePtr==null||ptr.compareTo(firstFreePtr)<0){
					firstIndex=i;
					firstFreePtr=ptr;
				}
			}
			
			if(firstFreePtr==null) break;
			limitedPos=firstFreePtr.getValue();
			
			Chunk freeChunk=cluster.getChunk(firstFreePtr);
			Chunk toMerge  =freeChunk.nextPhysical();
			
			if(toMerge==null){
				int toRemove=firstIndex;
				cluster.batchFree(()->{
					freeChunks.removeElement(toRemove);
					cluster.free(freeChunk);
					while(freeChunks.countGreaterThan(Objects::isNull, 1)){
						freeChunks.removeElement(freeChunks.indexOfLast(null));
					}
				});
				if(lastFreed) break;
				lastFreed=true;
				continue;
			}
			
			//TODO: this should not be a thing, freeing algorithm needs improvements
			// Case: Listed free, queued, queued, listed free
			// Result: Listed free (w 2 merged), listed free
			if(!toMerge.isUsed()){
				int index=freeChunks.indexOf(toMerge.getPtr());
				if(index!=-1){
					freeChunks.removeElement(index);
					cluster.free(toMerge);
					continue;
				}
			}
			if(!consumeFreeChunkStart(cluster, freeChunks, toMerge, firstIndex)){
				Chunk chunk=AllocateTicket.bytes(toMerge.getCapacity())
				                          .shouldDisableResizing(toMerge.isNextDisabled())
				                          .submit(cluster);
				chunk.modifyAndSave(c->c.setIsUserData(toMerge.isUserData()));
				cluster.copyDataAndMoveChunk(toMerge, chunk);
			}
		}
	}
	
	
	static void memoryReorder(Cluster cluster, IOList<ChunkPointer> freeChunks, Instant end) throws IOException, ActionStopException{
		boolean ending=false;
		while(true){
			timeout(end);
			Iterator<Chunk>     physicalOrder=cluster.getFirstChunk().physicalIterator().iterator();
			PairM<Chunk, Chunk> missMatch    =new PairM<>();
			var stackO=cluster.memoryWalk(ptr->{
				Chunk c=physicalOrder.next();
				if(c==null) return false;
				if(!ptr.headPtr().equals(c.getPtr())){
					missMatch.obj1=c;
					missMatch.obj2=cluster.getChunk(ptr.headPtr());
					return true;
				}
				return false;
			});
			if(stackO.isEmpty()){
				shrinkFreeChunks(cluster, freeChunks);
				if(ending) return;
				ending=true;
				continue;
			}
			
			Chunk clearStart=missMatch.obj1;
			Chunk toMoveIn  =missMatch.obj2;
			
			
			ChunkPointer startPtr=clearStart.getPtr();
			Chunk        start;
			freeLoop:
			while(true){
				start=cluster.getChunk(startPtr);
				for(Chunk chunk : start.physicalIterator()){
					if(!chunk.isUsed()){
						if(cluster.isLastPhysical(chunk)){
							shrinkFreeChunks(cluster, freeChunks);
							break freeLoop;
						}
						continue;
					}
					
					if(!start.isUsed()){
						if(consumeFreeChunkStart(cluster, freeChunks, toMoveIn, freeChunks.indexOf(startPtr))){
							break freeLoop;
						}
					}
					cluster.copyDataAndMoveChunk(chunk, AllocateTicket.approved(ch->ch.getPtr().compareTo(chunk.getPtr())>0));
					break;
				}
			}
		}
	}
	
	static void shrinkFreeChunks(Cluster cluster, IOList<ChunkPointer> freeChunks) throws IOException{
		cluster.batchFree(()->{
			loop:
			while(true){
				for(Iterator<ChunkPointer> iter=freeChunks.iterator();iter.hasNext();){
					var ptr=iter.next();
					if(ptr==null){
						iter.remove();
						continue loop;
					}
					Chunk ch=cluster.getChunk(ptr);
					if(cluster.isLastPhysical(ch)){
						iter.remove();
						cluster.free(ch);
						continue loop;
					}
				}
				break;
			}
		});
	}
}
