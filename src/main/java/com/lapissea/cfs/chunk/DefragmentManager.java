package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.*;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class DefragmentManager{
	
	public void defragment(Cluster cluster) throws IOException{
		try(var ignored=cluster.getMemoryManager().openDefragmentMode()){
			LogUtil.println("Defragmenting...");
			
			scanFreeChunks(cluster);
			
			mergeChains(cluster);
			
			scanFreeChunks(cluster);
			
			reorder(cluster);
			
			mergeChains(cluster);
			scanFreeChunks(cluster);
		}
	}
	
	private void reorder(final Cluster cluster) throws IOException{
		reallocateUnmanaged(cluster, (HashIOMap<?, ?>)cluster.getTemp());
		
		new MemoryWalker((HashIOMap<?, ?>)cluster.getTemp()).walk(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
				if(instance instanceof IOInstance.Unmanaged u){
					reallocateUnmanaged(cluster, u);
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
		});
	}
	
	private void mergeChains(Cluster cluster) throws IOException{
		while(true){
			Chunk fragmentedChunk;
			{
				var fragmentedChunkOpt=cluster.getFirstChunk().chunksAhead().stream().skip(1).filter(Chunk::hasNextPtr).findFirst();
				if(fragmentedChunkOpt.isEmpty()) break;
				fragmentedChunk=fragmentedChunkOpt.get();
			}
			
			long requiredSize=fragmentedChunk.chainSize();
			
			
			if(fragmentedChunk.getBodyNumSize().canFit(requiredSize)){
				
				long createdCapacity=0;
				{
					Chunk next=fragmentedChunk;
					while(fragmentedChunk.getCapacity()+createdCapacity<requiredSize+fragmentedChunk.getHeaderSize()+1){
						next=next.nextPhysical();
						if(next==null) throw new NotImplementedException("last but has next?");
						
						var frees=cluster.getMemoryManager().getFreeChunks();
						
						var freeIndex=frees.indexOf(next.getPtr());
						if(freeIndex!=-1){
							frees.remove(freeIndex);
						}else{
							if(DEBUG_VALIDATION){
								var chain=fragmentedChunk.collectNext();
								if(chain.contains(next)){
									throw new NotImplementedException("Special handling for already chained?");
								}
							}
							
							Chunk c=next;
							var newChunk=AllocateTicket
								.bytes(next.getSize())
								.shouldDisableResizing(next.getNextSize()==NumberSize.VOID)
								.withNext(next.getNextPtr())
								.withDataPopulated((p, io)->{
									byte[] data=p.getSource().read(c.dataStart(), Math.toIntExact(c.getSize()));
									io.write(data);
								})
								.submit(cluster);
							
							moveChunkExact(cluster, next.getPtr(), newChunk.getPtr());
						}
						
						createdCapacity+=next.totalSize();
					}
				}
				
				
				var grow          =requiredSize-fragmentedChunk.getCapacity();
				var remainingSpace=createdCapacity-grow;
				
				
				var remainingData=
					new ChunkBuilder(cluster, ChunkPointer.of(fragmentedChunk.dataStart()+requiredSize))
						.withExplicitNextSize(NumberSize.bySize(cluster.getSource().getIOSize()))
						.withCapacity(0)
						.create();
				
				Chunk fragmentData;
				try(var ignored=cluster.getSource().openIOTransaction()){
					
					remainingData.setCapacityAndModifyNumSize(remainingSpace-remainingData.getHeaderSize());
					assert remainingData.getCapacity()>0:remainingData;
					
					remainingData.writeHeader();
					remainingData=cluster.getChunk(remainingData.getPtr());
					
					try{
						fragmentedChunk.setCapacity(requiredSize);
					}catch(BitDepthOutOfSpaceException e){
						throw new ShouldNeverHappenError("This should be guarded by the canFit check");
					}
					
					fragmentData=fragmentedChunk.next();
					fragmentedChunk.clearNextPtr();
					
					try(var dest=fragmentedChunk.ioAt(fragmentedChunk.getSize());
					    var src=fragmentData.io()){
						src.transferTo(dest);
					}
					fragmentedChunk.syncStruct();
				}
				
				fragmentData.freeChaining();
				remainingData.freeChaining();
				
			}else{
				var newChunk=AllocateTicket
					.bytes(requiredSize)
					.shouldDisableResizing(fragmentedChunk.getNextSize()==NumberSize.VOID)
					.withDataPopulated((p, io)->{
						try(var old=fragmentedChunk.io()){
							old.transferTo(io);
						}
					})
					.submit(cluster);
				
				moveChunkExact(cluster, fragmentedChunk.getPtr(), newChunk.getPtr());
				fragmentedChunk.freeChaining();
			}
		}
	}
	
	private void scanFreeChunks(Cluster cluster) throws IOException{
		
		var activeChunks      =new ChunkSet();
		var unreferencedChunks=new ChunkSet();
		var knownFree         =new ChunkSet(cluster.getMemoryManager().getFreeChunks());
		
		activeChunks.add(cluster.getFirstChunk());
		unreferencedChunks.add(cluster.getFirstChunk());
		
		UnsafeConsumer<Chunk, IOException> pushChunk=chunk->{
			var ptr=chunk.getPtr();
			if(activeChunks.min()>ptr.getValue()) return;
			if(activeChunks.contains(ptr)&&!unreferencedChunks.contains(ptr)) return;
			
			while(true){
				if(activeChunks.isEmpty()) break;
				
				var last=activeChunks.last();
				
				if(last.compareTo(ptr)>=0){
					break;
				}
				var next=last.dereference(cluster).nextPhysical();
				if(next==null) break;
				activeChunks.add(next);
				if(next.getPtr().equals(ptr)||!knownFree.contains(next)){
					unreferencedChunks.add(next);
				}
			}
			
			unreferencedChunks.remove(ptr);
			if(activeChunks.trueSize()>1&&activeChunks.first().equals(ptr)){
				activeChunks.removeFirst();
			}
			
			var o=unreferencedChunks.optionalMin();
			if(o.isEmpty()&&activeChunks.trueSize()>1){
				var last=activeChunks.last();
				activeChunks.clear();
				activeChunks.add(last);
			}
			
			while(o.isPresent()){
				var minPtr=o.getAsLong();
				o=OptionalLong.empty();
				while(activeChunks.trueSize()>1&&(ptr.equals(activeChunks.first())||activeChunks.first().compareTo(minPtr)<0||knownFree.contains(activeChunks.first()))){
					activeChunks.removeFirst();
				}
			}
		};
		
		cluster.rootWalker().walk(true, ref->{
			if(ref.isNull()) return;
			for(Chunk chunk : ref.getPtr().dereference(cluster).collectNext()){
				pushChunk.accept(chunk);
			}
		});
		
		if(!unreferencedChunks.isEmpty()){
			List<Chunk> unreferenced=new ArrayList<>(Math.toIntExact(unreferencedChunks.size()));
			for(var ptr : unreferencedChunks){
				unreferenced.add(ptr.dereference(cluster));
			}
			
			LogUtil.println("found unknown free chunks:", unreferenced);
			
			cluster.getMemoryManager().free(unreferenced);
		}
	}
	
	private void moveChunkExact(final Cluster cluster, ChunkPointer oldChunk, ChunkPointer newChunk) throws IOException{
		
		cluster.rootWalker().walk(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
				if(value.getPtr().equals(oldChunk)){
					field.setReference(instance, new Reference(newChunk, value.getOffset()));
					try(var io=instanceReference.io(cluster)){
						pipe.write(cluster, io, instance);
					}
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
				if(value.equals(oldChunk)){
					field.set(null, instance, newChunk);
					try(var io=instanceReference.io(cluster)){
						pipe.write(cluster, io, instance);
					}
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
		});
		
	}
	
	private <T extends IOInstance.Unmanaged<T>> void reallocateUnmanaged(Cluster cluster, T instance) throws IOException{
		var oldRef=instance.getReference();
		var pip   =instance.getPipe();
		
		var siz=pip.calcUnknownSize(cluster, instance, WordSpace.BYTE);
		
		var newCh=AllocateTicket.bytes(siz).withDataPopulated((p, io)->{
			oldRef.withContext(p).io(src->src.transferTo(io));
		}).submit(cluster);
		var newRef=newCh.getPtr().makeReference();
		
		var ptrsToFree=moveReference(cluster, oldRef, newRef);
		instance.notifyReferenceMovement(newRef);
		cluster.getMemoryManager().freeChains(ptrsToFree);
	}
	
	private Set<ChunkPointer> moveReference(final Cluster cluster, Reference oldRef, Reference newRef) throws IOException{
//		LogUtil.println("moving", oldRef, "to", newRef);
		boolean[]         found ={false};
		Set<ChunkPointer> toFree=new HashSet<>();
		cluster.rootWalker().walk(new MemoryWalker.PointerRecord(){
			boolean foundCh;
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
				var ptr=oldRef.getPtr();
				if(value.getPtr().equals(ptr)){
					
					if(toFree.contains(ptr)){
						toFree.remove(ptr);
					}else if(!foundCh){
						toFree.add(ptr);
						foundCh=true;
					}
				}
				
				if(value.equals(oldRef)){
					field.setReference(instance, newRef);
					found[0]=true;
					return MemoryWalker.IterationOptions.SAVE_AND_END;
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
		});
		if(!found[0]){
			throw new IOException("Failed to find "+oldRef);
		}
		return toFree;
	}
	
}
