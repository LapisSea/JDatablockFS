package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.instancepipe.ObjectPipe;
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
import static com.lapissea.cfs.type.MemoryWalker.CONTINUE;
import static com.lapissea.cfs.type.MemoryWalker.END;
import static com.lapissea.cfs.type.MemoryWalker.SAVE;

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
			
			List<Chunk> toFree=new ArrayList<>();
			
			cluster.rootWalker().walk(new MemoryWalker.PointerRecord(){
				@Override
				public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference) throws IOException{
					if(instance instanceof IOInstance.Unmanaged u){
						var p   =u.getReference().getPtr();
						var user=findReferenceUser(cluster, u.getReference());
						if(user.getPtr().compareTo(p)>0){
							reallocateUnmanaged(cluster, u, c->c.getPtr().compareTo(user.getPtr())>0);
							run[0]=true;
							return CONTINUE;
						}
					}else{
						boolean after=instanceReference.getPtr().compareTo(valueReference.getPtr())<0;
						if(after) return CONTINUE;
						run[0]=true;
						
						var type=field.getAccessor().getType();
						
						if(IOInstance.isUnmanaged(type)){
							reallocateUnmanaged(cluster, (IOInstance.Unmanaged)field.get(null, instance));
							return END;
						}
						
						var ch=valueReference.getPtr().dereference(cluster);
						ch.streamNext().forEach(toFree::add);
						
						var newCh=AllocateTicket.withData((ObjectPipe)field.getReferencedPipe(instance), cluster, field.get(null, instance))
						                        .withBytes(ch.getSize())
						                        .withApproval(c->c.getPtr().getValue()>instanceReference.getPtr().getValue())
						                        .submit(cluster);
						
						field.setReference(instance, newCh.getPtr().makeReference());
						if(toFree.size()>8){
							return END|SAVE;
						}
						return CONTINUE|SAVE;
					}
					return CONTINUE;
				}
				@Override
				public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value) throws IOException{
					return CONTINUE;
				}
			});
			
			cluster.getMemoryManager().free(toFree);
		}
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
		}
	}
	
	private void reallocateAndMove(Cluster cluster, Chunk fragmentedChunk, long requiredSize) throws IOException{
		var likelyChain=fragmentedChunk.streamNext().limit(3).count()>2;
		var newChunk=AllocateTicket
			             .bytes(requiredSize)
			             .withDataPopulated((p, io)->{
				             try(var old=fragmentedChunk.io()){
					             io.setSize(old.getSize());
					             old.transferTo(io);
					             if(io.getSize()!=io.getPos()){
						             throw new IllegalStateException();
					             }
				             }
			             })
			             .withExplicitNextSize(likelyChain?Optional.empty():Optional.of(NumberSize.bySize(cluster.getSource().getIOSize())))
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
			public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference){
				if(valueReference.getPtr().equals(oldChunk)){
					field.setReference(instance, new Reference(newChunk, valueReference.getOffset()));
					return CONTINUE|SAVE;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value){
				if(value.equals(oldChunk)){
					field.set(null, instance, newChunk);
					return CONTINUE|SAVE;
				}
				return CONTINUE;
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
			public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference){
				var ptr=oldRef.getPtr();
				if(valueReference.getPtr().equals(ptr)){
					
					if(toFree.contains(ptr)){
						toFree.remove(ptr);
					}else if(!foundCh){
						toFree.add(ptr);
						foundCh=true;
					}
				}
				
				if(valueReference.equals(oldRef)){
					field.setReference(instance, newRef);
					found[0]=true;
					return SAVE|END;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value){
				return CONTINUE;
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
			public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference){
				if(valueReference.equals(ref)){
					found[0]=instanceReference;
					return END;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value){
				return CONTINUE;
			}
		});
		if(found[0]==null){
			throw new IOException("Failed to find ");
		}
		return found[0];
	}
	
}
