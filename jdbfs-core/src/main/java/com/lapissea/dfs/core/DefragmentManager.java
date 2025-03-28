package com.lapissea.dfs.core;

import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkBuilder;
import com.lapissea.dfs.core.chunk.ChunkSet;
import com.lapissea.dfs.exceptions.MalformedFile;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.util.MathUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.logging.Log.debug;
import static com.lapissea.dfs.logging.Log.warn;
import static com.lapissea.dfs.type.MemoryWalker.CONTINUE;
import static com.lapissea.dfs.type.MemoryWalker.END;
import static com.lapissea.dfs.type.MemoryWalker.HOLDER_COPY;
import static com.lapissea.dfs.type.MemoryWalker.SAVE;

public class DefragmentManager{
	
	private final Cluster parent;
	
	public DefragmentManager(Cluster parent){
		this.parent = parent;
	}
	
	public void defragment() throws IOException{
		debug("Defragmenting...");
		
		scanFreeChunks(FreeFoundAction.WARN_AND_LIST);
		
		mergeChains(parent);
		optimizeFreeChunks(parent);
		
		mergeChains(parent);
		optimizeFreeChunks(parent);
		
		pack(parent);
		int a = 0;
	}
	
	private void pack(final Cluster cluster) throws IOException{
		// traceCall();
		
		var freeChunks = cluster.getMemoryManager().getFreeChunks();
		while(!freeChunks.isEmpty()){
			Chunk last = freeChunks.getLast().dereference(cluster);
			while(!last.checkLastPhysical()){
				last = last.nextPhysical();
			}
			
			var siz  = cluster.getSource().getIOSize();
			var move = moveReference(cluster, last.getPtr(), t -> t.withApproval(c -> c.getPtr().getValue()<siz), true);
			if(move.isEmpty()) break;
		}
	}
	private void optimizeFreeChunks(final Cluster cluster) throws IOException{
		// traceCall();
		wh:
		while(true){
			
			var frees = cluster.getMemoryManager().getFreeChunks();
			iter:
			for(var iter = frees.listIterator(frees.size()); iter.hasPrevious(); ){
				var freeNext = iter.ioPrevious().dereference(cluster);
				if(!iter.hasPrevious()) break;
				var freeFirst = iter.ioPrevious().dereference(cluster);
				iter.ioNext();
				boolean surround = false;
				
				long sumMove = 0;
				var  ch      = freeFirst;
				for(int i = 0; i<16; i++){
					ch = ch.nextPhysical();
					if(ch == null) continue iter;
					
					sumMove += ch.totalSize();
					if(sumMove>=256) break;
					if(freeNext.getPtr().equals(ch.dataEnd())){
						surround = true;
						break;
					}
				}
				if(!surround) continue;
				
				var firstNext = freeFirst.nextPhysical();
				var limit     = firstNext.getPtr().getValue();
				
				var move = moveReference(cluster, firstNext.getPtr(), t -> t.withPositionMagnet(0).withApproval(c -> c.getPtr().getValue()<limit), true);
				
				if(move.hasAny()){
					continue wh;
				}
			}
			
			break;
		}
		
		cluster.getMemoryManager().getFreeChunks().trim();
	}
	
	//TODO: remove allowUnmanaged and introduce concept of unmanaged instance tracking to enable proper notification of movement
	public static MoveBuffer moveReference(Cluster cluster, ChunkPointer toMove, Function<AllocateTicket, AllocateTicket> ticketFn, boolean allowUnmanaged) throws IOException{
		{
			var ch = toMove.dereference(cluster);
			var ticket = AllocateTicket.bytes(ch.chainSize())
			                           .withExplicitNextSize(Optional.of(ch.getNextSize()));
			ticket = ticketFn.apply(ticket);
			if(!ticket.canSubmit(cluster)){
				return new MoveBuffer();
			}
		}
		
		List<Chunk> toFree = new ArrayList<>();
		var record = new MemoryWalker.PointerRecord(){
			MoveBuffer move = new MoveBuffer();
			@SuppressWarnings({"unchecked", "rawtypes"})
			@Override
			public <T extends IOInstance<T>> int log(
				Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference, Holder holder) throws IOException{
				var ptr = valueReference.getPtr();
				if(!ptr.equals(toMove) || valueReference.getOffset() != 0){
					return CONTINUE;
				}
				
				var type = field.getType();
				
				if(IOInstance.isUnmanaged(type)){
					if(allowUnmanaged){
						move = reallocateUnmanaged(cluster, (IOInstance.Unmanaged)field.get(null, instance), ticketFn);
					}else Log.trace("Disallowed move of unmanaged");
					return END;
				}
				
				var ch = ptr.dereference(cluster);
				
				Chunk newCh = copyCh(instanceReference, ch);
				if(newCh == null){
					return END;
				}
				ch.addChainTo(toFree);
				
				var newPtr = newCh.getPtr();
				field.setReference(instance, newPtr.makeReference());
				move = new MoveBuffer(toMove, newPtr);
				
				return END|SAVE;
			}
			private Chunk copyCh(Reference instanceReference, Chunk ch) throws IOException{
				var ticket = AllocateTicket.bytes(ch.chainSize())
				                           .withExplicitNextSize(Optional.of(ch.getNextSize()))
				                           .withDataPopulated((p, dIo) -> {
					                           ch.io(sIo -> sIo.transferTo(dIo));
				                           });
				ticket = ticketFn.apply(ticket);
				if(ticket.positionMagnet().isEmpty()){
					ticket = ticket.withPositionMagnet(instanceReference.calcGlobalOffset(cluster));
				}
				
				//Open IO in order to lock the chunk and prevent it from being deallocated as a side effect
				try(var ignored = ch.io()){
					return ticket.submit(cluster);
				}
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(
				Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder) throws IOException{
				if(!value.equals(toMove)){
					return CONTINUE;
				}
				var ch = value.dereference(cluster);
				
				Chunk newCh = copyCh(instanceReference, ch);
				if(newCh == null){
					return END;
				}
				
				var newPtr = newCh.getPtr();
				ch.addChainTo(toFree);
				move = new MoveBuffer(toMove, newPtr);
				
				if(field.isReadOnly()){
					holder.value = newPtr;
					return END|HOLDER_COPY;
				}else{
					field.set(null, instance, newPtr);
					return END|SAVE;
				}
			}
		};
		
		cluster.rootWalker(record, false).walk();
		
		cluster.getMemoryManager().free(toFree);
		return record.move;
	}
	
	private void reorder(final Cluster cluster) throws IOException{
		boolean[] run = {true};
		while(run[0]){
			run[0] = false;
			
			List<Chunk> toFree = new ArrayList<>();
			
			cluster.rootWalker(new MemoryWalker.PointerRecord(){
				@SuppressWarnings({"rawtypes", "unchecked"})
				@Override
				public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference, Holder holder) throws IOException{
					if(instance instanceof IOInstance.Unmanaged u){
						var p    = u.getPointer();
						var user = findReferenceUser(cluster, p.makeReference());
						if(user.getPtr().compareTo(p)>0){
							var move = reallocateUnmanaged(cluster, u, t -> t.withApproval(c -> c.getPtr().compareTo(user.getPtr())>0));
							if(move.hasAny()) run[0] = true;
							return CONTINUE;
						}
					}else{
						boolean after = instanceReference.getPtr().compareTo(valueReference.getPtr())<0;
						if(after) return CONTINUE;
						
						var type = field.getType();
						
						if(IOInstance.isUnmanaged(type)){
							var move = reallocateUnmanaged(cluster, (IOInstance.Unmanaged)field.get(null, instance));
							if(move.hasAny()) run[0] = true;
							return END;
						}
						
						run[0] = true;
						
						var ch = valueReference.getPtr().dereference(cluster);
						ch.addChainTo(toFree);
						
						var newCh = AllocateTicket.withData((ObjectPipe)field.getReferencedPipe(instance), cluster, field.get(null, instance))
						                          .withBytes(ch.getSize())
						                          .withApproval(c -> c.getPtr().getValue()>instanceReference.getPtr().getValue())
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
				public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
					return CONTINUE;
				}
			}, false).walk();
			
			cluster.getMemoryManager().free(toFree);
		}
	}
	
	private void mergeChains(Cluster cluster) throws IOException{
		// traceCall();
		var startingChunk = cluster.getFirstChunk();
		while(true){
			Chunk fragmentedChunk;
			{
				var fragmentedChunkOpt = startingChunk.chunksAhead().skip(1).firstMatching(Chunk::hasNextPtr);
				if(fragmentedChunkOpt.isEmpty()) break;
				fragmentedChunk = fragmentedChunkOpt.get();
				
				startingChunk = fragmentedChunk;
				
				while(true){
					var fptr  = fragmentedChunk.getPtr();
					var chRef = startingChunk.chunksAhead().skip(1).firstMatching(c -> c.getNextPtr().equals(fptr));
					if(chRef.isEmpty()) break;
					fragmentedChunk = chRef.get();
				}
			}
			
			long requiredSize = fragmentedChunk.chainCapacity();
			
			reallocateAndMove(cluster, fragmentedChunk, requiredSize);
			
			var cached = startingChunk.getDataProvider().getChunkCached(startingChunk.getPtr());
			if(cached != startingChunk){
				startingChunk = cluster.getFirstChunk();
			}
		}
	}
	
	private void reallocateAndMove(Cluster cluster, Chunk fragmentedChunk, long requiredSize) throws IOException{
		var likelyChain = fragmentedChunk.chainLength(3)>2;
		var newChunk = AllocateTicket
			               .bytes(requiredSize)
			               .withDataPopulated((p, io) -> {
				               try(var old = fragmentedChunk.io()){
					               io.setSize(old.getSize());
					               old.transferTo(io);
					               if(io.getSize() != io.getPos()){
						               throw new IllegalStateException();
					               }
				               }
			               })
			               .withExplicitNextSize(likelyChain? Optional.empty() : Optional.of(NumberSize.bySize(cluster.getSource().getIOSize())))
			               .submit(cluster);
		
		var moved = moveChunkExact(cluster, fragmentedChunk.getPtr(), newChunk.getPtr());
		if(moved){
			fragmentedChunk.freeChaining();
		}else{
			Log.trace("Failed reallocate and move: {}#yellow", fragmentedChunk);
		}
	}
	private void moveNextAndMerge(Cluster cluster, Chunk fragmentedChunk, long requiredSize) throws IOException{
		try(var ignored1 = cluster.getMemoryManager().openDefragmentMode()){
			long createdCapacity = 0;
			{
				Chunk next = fragmentedChunk;
				while(fragmentedChunk.getCapacity() + createdCapacity<requiredSize + fragmentedChunk.getHeaderSize() + 1){
					next = next.nextPhysical();
					if(next == null) throw new NotImplementedException("last but has next?");
					
					var frees = cluster.getMemoryManager().getFreeChunks();
					
					var freeIndex = frees.indexOf(next.getPtr());
					if(freeIndex != -1){
						frees.remove(freeIndex);
					}else{
						if(DEBUG_VALIDATION){
							var chain = fragmentedChunk.collectNext();
							if(chain.contains(next)){
								throw new NotImplementedException("Special handling for already chained?");
							}
						}
						
						Chunk c = next;
						var newChunk = AllocateTicket
							               .bytes(next.getSize())
							               .withNext(next.getNextPtr())
							               .withDataPopulated((p, io) -> {
								               byte[] data = p.getSource().read(c.dataStart(), Math.toIntExact(c.getSize()));
								               io.write(data);
							               })
							               .submit(cluster);
						
						var moved = moveChunkExact(cluster, next.getPtr(), newChunk.getPtr());
						if(!moved) throw new NotImplementedException();
					}
					
					createdCapacity += next.totalSize();
				}
			}
			
			
			var grow           = requiredSize - fragmentedChunk.getCapacity();
			var remainingSpace = createdCapacity - grow;
			
			
			var remainingData =
				new ChunkBuilder(cluster, ChunkPointer.of(fragmentedChunk.dataStart() + requiredSize))
					.withExplicitNextSize(NumberSize.bySize(cluster.getSource().getIOSize()))
					.withCapacity(0)
					.create();
			
			Chunk fragmentData;
			try(var ignored = cluster.getSource().openIOTransaction()){
				
				if(!remainingData.setCapacityAndModifyNumSize(remainingSpace - remainingData.getHeaderSize())){
					throw new AssertionError("Failed to set chunk size: " + remainingData);
				}
				
				remainingData.writeHeader();
				remainingData = cluster.getChunk(remainingData.getPtr());
				
				try{
					fragmentedChunk.setCapacity(requiredSize);
				}catch(OutOfBitDepth e){
					throw new ShouldNeverHappenError("This should be guarded by the canFit check");
				}
				
				fragmentData = Objects.requireNonNull(fragmentedChunk.next());
				fragmentedChunk.clearNextPtr();
				
				try(var dest = fragmentedChunk.ioAt(fragmentedChunk.getSize());
				    var src = fragmentData.io()){
					src.transferTo(dest);
				}
				fragmentedChunk.syncStruct();
			}
			
			fragmentData.freeChaining();
			remainingData.freeChaining();
		}
	}
	
	private ChunkSet findFreeChunks(Cluster cluster) throws IOException{
		var activeChunks       = new ChunkSet();
		var unreferencedChunks = new ChunkSet();
		var knownFree          = new ChunkSet(cluster.getMemoryManager().getFreeChunks());
		
		activeChunks.add(cluster.getFirstChunk());
		unreferencedChunks.add(cluster.getFirstChunk());
		
		UnsafeConsumer<Chunk, IOException> pushChunk = chunk -> {
			var ptr = chunk.getPtr();
			if(activeChunks.min()>ptr.getValue()) return;
			if(activeChunks.contains(ptr) && !unreferencedChunks.contains(ptr)) return;
			
			while(true){
				if(activeChunks.isEmpty()) break;
				
				var last = activeChunks.last();
				
				if(last.compareTo(ptr)>=0){
					break;
				}
				var next = last.dereference(cluster).nextPhysical();
				if(next == null) break;
				activeChunks.add(next);
				if(next.getPtr().equals(ptr) || !knownFree.contains(next)){
					unreferencedChunks.add(next);
				}
			}
			
			unreferencedChunks.remove(ptr);
			if(activeChunks.trueSize()>1 && activeChunks.first().equals(ptr)){
				activeChunks.removeFirst();
			}
			
			var o = unreferencedChunks.optionalMin();
			if(o.isEmpty() && activeChunks.trueSize()>1){
				var last = activeChunks.last();
				activeChunks.clear();
				activeChunks.add(last);
			}
			
			while(o.isPresent()){
				var minPtr = o.getAsLong();
				o = OptionalLong.empty();
				while(activeChunks.trueSize()>1 && (ptr.equals(activeChunks.first()) || activeChunks.first().compareTo(minPtr)<0 || knownFree.contains(activeChunks.first()))){
					activeChunks.removeFirst();
				}
			}
		};
		
		cluster.rootWalker(MemoryWalker.PointerRecord.of(ref -> {
			if(ref.isNull()) return;
			for(Chunk chunk : ref.getPtr().dereference(cluster).collectNext()){
				pushChunk.accept(chunk);
			}
		}), true).walk();
		
		return unreferencedChunks;
	}
	
	public enum FreeFoundAction{
		NOTHING,
		WARN_AND_LIST,
		WARN,
		ERROR
	}
	
	public void scanFreeChunks(FreeFoundAction action) throws IOException{
		var cluster = parent;
		
		var unreferencedChunks = findFreeChunks(cluster);
		if(unreferencedChunks.isEmpty()) return;
		
		switch(action){
			case null -> throw new IllegalArgumentException();
			case NOTHING -> { }
			case WARN_AND_LIST -> {
				warn("found unknown free chunks: {}", unreferencedChunks);
				
				List<Chunk> unreferenced = new ArrayList<>(MathUtil.snap((int)unreferencedChunks.trueSize(), 1, 50));
				for(var unreferencedChunk : unreferencedChunks){
					unreferenced.add(unreferencedChunk.dereference(cluster));
					
					if(unreferenced.size()>=64){
						cluster.getMemoryManager().free(unreferenced);
						unreferenced.clear();
					}
				}
				if(!unreferenced.isEmpty()){
					cluster.getMemoryManager().free(unreferenced);
				}
			}
			case WARN -> warn("found unknown free chunks: {}", unreferencedChunks);
			case ERROR -> throw new MalformedFile("found unknown free chunks: " + unreferencedChunks);
		}
	}
	
	private boolean moveChunkExact(final Cluster cluster, ChunkPointer oldChunk, ChunkPointer newChunk) throws IOException{
		
		var rec = new MemoryWalker.PointerRecord(){
			boolean moved;
			@Override
			public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference, Holder holder) throws IOException{
				if(valueReference.getPtr().equals(oldChunk)){
					try{
						field.setReference(instance, Reference.of(newChunk, valueReference.getOffset()));
						moved = true;
					}catch(VaryingSize.TooSmall ts){
						return END;//TODO: create mechanism to notify parent to expand
					}
					return END|SAVE;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
				if(value.equals(oldChunk)){
					if(field.isReadOnly()){
						holder.value = newChunk;
						return END|HOLDER_COPY;
					}
					try{
						field.set(null, instance, newChunk);
						moved = true;
					}catch(VaryingSize.TooSmall ts){
						return END;
					}
					return END|SAVE;
				}
				return CONTINUE;
			}
		};
		
		cluster.rootWalker(rec, false).walk();
		
		return rec.moved;
	}
	
	private <T extends IOInstance.Unmanaged<T>> MoveBuffer reallocateUnmanaged(Cluster cluster, T instance) throws IOException{
		return reallocateUnmanaged(cluster, instance, t -> t.withApproval(c -> c.getPtr().compareTo(instance.getPointer())>0));
	}
	private static <T extends IOInstance.Unmanaged<T>> MoveBuffer reallocateUnmanaged(Cluster cluster, T instance, Function<AllocateTicket, AllocateTicket> ticketFn) throws IOException{
		var oldPtr = instance.getPointer();
		
		var siz = oldPtr.dereference(cluster).chainSize();
		
		var ticket = AllocateTicket.bytes(siz)
		                           .withDataPopulated((p, io) -> oldPtr.dereference(p).io(src -> src.transferTo(io)));
		ticket = ticketFn.apply(ticket);
		
		var newCh = ticket.submit(cluster);
		if(newCh == null) return new MoveBuffer();
		var newPtr = newCh.getPtr();
		var newRef = newPtr.makeReference();
		
		var ptrsToFree = moveReference(cluster, oldPtr.makeReference(), newRef);
		instance.notifyReferenceMovement(newCh);
		cluster.getMemoryManager().freeChains(ptrsToFree);
		return new MoveBuffer(oldPtr, newPtr);
	}
	
	private static Set<ChunkPointer> moveReference(final Cluster cluster, Reference oldRef, Reference newRef) throws IOException{
		
		boolean[]         found  = {false};
		Set<ChunkPointer> toFree = new HashSet<>();
		cluster.rootWalker(new MemoryWalker.PointerRecord(){
			boolean foundCh;
			@Override
			public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference, Holder holder) throws IOException{
				var ptr = oldRef.getPtr();
				if(valueReference.getPtr().equals(ptr)){
					
					if(toFree.contains(ptr)){
						toFree.remove(ptr);
					}else if(!foundCh){
						toFree.add(ptr);
						foundCh = true;
					}
				}
				
				if(valueReference.equals(oldRef)){
					field.setReference(instance, newRef);
					found[0] = true;
					return SAVE|END;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
				return CONTINUE;
			}
		}, false).walk();
		if(!found[0]){
			throw new IOException("Failed to find " + oldRef);
		}
		return toFree;
	}
	
	private Reference findReferenceUser(final Cluster cluster, Reference ref) throws IOException{
		Reference[] found = {null};
		cluster.rootWalker(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference, Holder holder){
				if(valueReference.equals(ref)){
					found[0] = instanceReference;
					return END;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
				return CONTINUE;
			}
		}, false).walk();
		if(found[0] == null){
			throw new IOException("Failed to find ");
		}
		return found[0];
	}
	
}
