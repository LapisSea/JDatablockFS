package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class DefragmentManager{
	
	private final Cluster parent;
	
	public DefragmentManager(Cluster parent){
		this.parent=parent;
	}
	
	public void defragment() throws IOException{
		LogUtil.println("Defragmenting...");
		
		scanFreeChunks(parent);
		
		mergeChains(parent);
		
		scanFreeChunks(parent);
		
		reorder(parent);
		
		mergeChains(parent);
	}
	
	private void reorder(final Cluster cluster) throws IOException{
		boolean[] run={true};
		while(run[0]){
			run[0]=false;
			
			cluster.rootWalker().walk(new MemoryWalker.PointerRecord(){
				@Override
				public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
					if(instance instanceof IOInstance.Unmanaged u){
						var p   =u.getReference().getPtr();
						var user=findReferenceUser(cluster, u.getReference());
						if(user.getPtr().compareTo(p)>0){
							reallocateUnmanaged(cluster, u, c->c.getPtr().compareTo(user.getPtr())>0);
							run[0]=true;
							return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
						}
					}
					return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
				}
				@Override
				public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
					return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
				}
			});
		}
//		run[0]=true;
//		while(run[0]){
//			run[0]=false;
//
//			new MemoryWalker((HashIOMap<?, ?>)cluster.getTemp()).walk(new MemoryWalker.PointerRecord(){
//				@Override
//				public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
//					if(instance instanceof IOInstance.Unmanaged u){
//						var p   =u.getReference().getPtr();
//						for(ChunkPointer freeChunk : cluster.getMemoryManager().getFreeChunks()){
//							if(freeChunk.compareTo(p)>0) continue;
//							var c=freeChunk.dereference(cluster);
//							if(c.getCapacity()-Chunk.PIPE.getSizeDescriptor().requireMax(WordSpace.BYTE)>p.dereference(cluster).getSize()){
//								reallocateUnmanaged(cluster, u, ch->ch.getPtr().compareTo(p)<0);
//								run[0]=true;
//								return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
//							}
//						}
//					}
//					return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
//				}
//				@Override
//				public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
//					return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
//				}
//			});
//		}
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
			
			reallocateAndMove(cluster, fragmentedChunk, requiredSize);
			
			
			//TODO: moveNextAndMerge is not stable. Figure out what's wrong before enabling
//			if(fragmentedChunk.getBodyNumSize().canFit(requiredSize)){
//				TODO: make better logic for deciding if moving next is good
//				moveNextAndMerge(cluster, fragmentedChunk, requiredSize);
//			}else{
//				reallocateAndMove(cluster, fragmentedChunk, requiredSize);
//			}
		}
	}
	
	private void reallocateAndMove(Cluster cluster, Chunk fragmentedChunk, long requiredSize) throws IOException{
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
	private void moveNextAndMerge(Cluster cluster, Chunk fragmentedChunk, long requiredSize) throws IOException{
		try(var ignored1=cluster.getMemoryManager().openDefragmentMode()){
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
				
				fragmentData=Objects.requireNonNull(fragmentedChunk.next());
				fragmentedChunk.clearNextPtr();
				
				try(var dest=fragmentedChunk.ioAt(fragmentedChunk.getSize());
				    var src=fragmentData.io()){
					src.transferTo(dest);
				}
				fragmentedChunk.syncStruct();
			}
			
			fragmentData.freeChaining();
			remainingData.freeChaining();
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
			List<Chunk> unreferenced=new ArrayList<>(Math.toIntExact(unreferencedChunks.trueSize()));
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
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value){
				if(value.getPtr().equals(oldChunk)){
					field.setReference(instance, new Reference(newChunk, value.getOffset()));
					return MemoryWalker.IterationOptions.CONTINUE_AND_SAVE;
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value){
				if(value.equals(oldChunk)){
					field.set(null, instance, newChunk);
					return MemoryWalker.IterationOptions.CONTINUE_AND_SAVE;
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
		});
		
	}
	
	private <T extends IOInstance.Unmanaged<T>> void reallocateUnmanaged(Cluster cluster, T instance) throws IOException{
		reallocateUnmanaged(cluster, instance, c->c.getPtr().compareTo(instance.getReference().getPtr())>0);
	}
	private <T extends IOInstance.Unmanaged<T>> void reallocateUnmanaged(Cluster cluster, T instance, Predicate<Chunk> approve) throws IOException{
		var oldRef=instance.getReference();
		
		var siz=instance.getReference().getPtr().dereference(cluster).chainSize();
		
		var newCh=AllocateTicket.bytes(siz)
		                        .withApproval(approve)
		                        .withDataPopulated((p, io)->{
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
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value){
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
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value){
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
		});
		if(!found[0]){
			throw new IOException("Failed to find "+oldRef);
		}
		return toFree;
	}
	
	private Reference findReferenceUser(final Cluster cluster, Reference ref) throws IOException{
		Reference[] found={null};
		cluster.rootWalker().walk(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value){
				if(value.equals(ref)){
					found[0]=instanceReference;
					return MemoryWalker.IterationOptions.END;
				}
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
			@Override
			public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value){
				return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
			}
		});
		if(found[0]==null){
			throw new IOException("Failed to find ");
		}
		return found[0];
	}
	
}
