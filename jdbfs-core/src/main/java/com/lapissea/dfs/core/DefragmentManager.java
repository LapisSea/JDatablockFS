package com.lapissea.dfs.core;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkSet;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.core.chunk.PhysicalExtent;
import com.lapissea.dfs.core.chunk.PhysicalExtentWalker;
import com.lapissea.dfs.exceptions.MalformedFile;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.util.MathUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
	
	/**
	 * Compacts existing free space synchronously. Must have exclusive, synchronous access to database.
	 * Request unmanaged roots again after done, previously held handles can refer to moved allocations. Best ran after database open
	 */
	public void defragment() throws IOException{
		debug("Compacting...");
		try{
			scanFreeChunks(FreeFoundAction.WARN_AND_LIST);
			mergeChains();
			mergeFreeRegions();
			compact();
			mergeFreeRegions();
			evacuateBlockers();
			compact();
		}finally{
			parent.invalidateRootCache();
		}
	}
	
	private void compact() throws IOException{
		while(true){
			var chunks = new ArrayList<Chunk>();
			for(var extent : new PhysicalExtentWalker(parent, parent.getFirstChunk().getPtr())){
				if(extent instanceof Chunk chunk && chunk != parent.getFirstChunk()){
					chunks.add(chunk);
				}
			}
			boolean moved = false;
			for(int i = chunks.size() - 1; i>=0; i--){
				var chunk = chunks.get(i);
				var move  = moveReference(parent, chunk.getPtr(), ticket -> ticket, true);
				if(move.hasAny()){
					moved = true;
					break;
				}
			}
			if(!moved) return;
		}
	}
	
	private void mergeFreeRegions() throws IOException{
		var  maxChunks = ConfigDefs.DEFRAG_MERGE_MAX_CHUNKS.resolveVal();
		long maxBytes  = ConfigDefs.DEFRAG_MERGE_MAX_BYTES.resolveVal();
		while(true){
			var     frees = parent.getMemoryManager().getFreeChunks();
			boolean moved = false;
			for(long k = 0; k<frees.size() - 1; k++){
				var firstFree = FreeChunk.read(parent, frees.get(k));
				var nextFree  = frees.get(k + 1);
				
				int     count   = 0;
				long    bytes   = 0;
				boolean reached = false;
				for(long pos = firstFree.dataEnd(); pos<nextFree.getValue(); ){
					if(count>=maxChunks || bytes>maxBytes) break;
					var extent = PhysicalExtent.read(parent, ChunkPointer.of(pos));
					bytes += extent.totalSize();
					count++;
					pos = extent.dataEnd();
					if(pos == nextFree.getValue()) reached = true;
				}
				if(!reached || count == 0) continue;
				
				var move = moveReference(parent, ChunkPointer.of(firstFree.dataEnd()), ticket -> ticket, true);
				if(move.hasAny()){
					moved = true;
					break;
				}
			}
			if(!moved) return;
		}
	}
	
	private record Evac(ChunkPointer victim, long lo, long hi){ }
	
	private void evacuateBlockers() throws IOException{
		var  maxChunks = ConfigDefs.DEFRAG_MERGE_MAX_CHUNKS.resolveVal();
		long maxBytes  = ConfigDefs.DEFRAG_MERGE_MAX_BYTES.resolveVal();
		var  evacs     = new ArrayList<Evac>();
		
		var frees = parent.getMemoryManager().getFreeChunks();
		for(long k = 0; k<frees.size() - 1; k++){
			var  firstFree = FreeChunk.read(parent, frees.get(k));
			var  nextFree  = FreeChunk.read(parent, frees.get(k + 1));
			long lo        = firstFree.getPtr().getValue();
			long hi        = nextFree.dataEnd();
			
			var     gap     = new ArrayList<ChunkPointer>();
			int     count   = 0;
			long    bytes   = 0;
			boolean reached = false;
			for(long pos = firstFree.dataEnd(); pos<nextFree.getPtr().getValue(); ){
				if(count>=maxChunks || bytes>maxBytes) break;
				
				var extent = PhysicalExtent.read(parent, ChunkPointer.of(pos));
				
				bytes += extent.totalSize();
				count++;
				
				if(extent instanceof Chunk){
					gap.add(extent.getPtr());
				}
				
				pos = extent.dataEnd();
				if(pos == nextFree.getPtr().getValue()){
					reached = true;
				}
			}
			if(reached){
				for(var victim : gap){
					evacs.add(new Evac(victim, lo, hi));
				}
			}
		}
		
		for(var evac : evacs){
			try{
				moveReference(parent, evac.victim(), ticket -> ticket.withApproval(candidate -> {
					long pos = candidate.getPtr().getValue();
					return pos<evac.lo() || pos>=evac.hi();
				}));
			}catch(RuntimeException failure){
				if(!isTooSmall(failure)) throw failure;
			}
		}
	}
	
	private void mergeChains() throws IOException{
		for(var head : findFragmentedChainHeads()){
			consolidateChain(head);
		}
	}
	
	private List<Chunk> findFragmentedChainHeads() throws IOException{
		var pointedTo = new HashSet<ChunkPointer>();
		var heads     = new ArrayList<Chunk>();
		for(var extent : new PhysicalExtentWalker(parent, parent.getFirstChunk().getPtr())){
			if(extent instanceof Chunk chunk && chunk.hasNextPtr()){
				pointedTo.add(chunk.getNextPtr());
				heads.add(chunk);
			}
		}
		heads.removeIf(chunk -> pointedTo.contains(chunk.getPtr()));
		return heads;
	}
	
	private boolean consolidateChain(Chunk head) throws IOException{
		var ticket = AllocateTicket.bytes(head.chainCapacity())
		                           .withExplicitNextSize(Optional.empty())
		                           .withDataPopulated((p, io) -> {
			                           try(var old = head.io()){
				                           io.setSize(old.getSize());
				                           old.transferTo(io);
				                           if(io.getSize() != io.getPos()) throw new IllegalStateException();
			                           }
		                           });
		try(var transaction = parent.getSource().startTransaction()){
			try{
				var newChunk = ticket.submit(parent);
				rewriteReferences(parent, head.getPtr(), newChunk.getPtr());
			}catch(RuntimeException failure){
				if(!isTooSmall(failure)) throw failure;
				return false;
			}
			head.freeChaining();
			transaction.commit();
			return true;
		}
	}
	
	private static boolean isTooSmall(Throwable failure){
		for(var e = failure; e != null; e = e.getCause()){
			if(e instanceof VaryingSize.TooSmall) return true;
		}
		return false;
	}
	
	/**
	 * Moves one physical chunk, without flattening its chain.
	 * References to later chunks and offsets within the chain keep their existing meaning.
	 */
	public static MoveBuffer moveReference(Cluster cluster, ChunkPointer toMove, Function<AllocateTicket, AllocateTicket> ticketFn) throws IOException{
		try{
			return moveReference(cluster, toMove, ticketFn, false);
		}finally{
			cluster.invalidateRootCache();
		}
	}
	
	private static MoveBuffer moveReference(Cluster cluster, ChunkPointer toMove, Function<AllocateTicket, AllocateTicket> ticketFn, boolean earlier) throws IOException{
		try(var transaction = cluster.getSource().startTransaction()){
			var result = moveReference0(cluster, toMove, ticketFn, earlier);
			transaction.commit();
			return result;
		}
	}
	
	private static MoveBuffer moveReference0(Cluster cluster, ChunkPointer toMove, Function<AllocateTicket, AllocateTicket> ticketFn, boolean earlier) throws IOException{
		if(toMove.equals(cluster.getFirstChunk().getPtr())) return new MoveBuffer();
		var source  = toMove.dereference(cluster);
		var manager = cluster.getMemoryManager();
		var ticket = ticketFn.apply(
			AllocateTicket.bytes(source.getCapacity())
			              .withNext(source.getNextPtr())
			              .withExplicitNextSize(Optional.of(source.getNextSize()))
		);
		
		ticket = ticket.withApproval(candidate -> candidate.getCapacity()>=source.getCapacity() &&
		                                          candidate.getNextPtr().equals(source.getNextPtr()));
		
		// The "all but the last chunks must be full" should be respected
		if(source.hasNextPtr()){
			ticket = ticket.withApproval(candidate -> candidate.getCapacity() == source.getCapacity());
		}
		
		if(earlier && !manager.canAllocBefore(ticket, toMove)){
			return new MoveBuffer();
		}
		
		manager.checkCanFree(source);
		boolean[] found = {false};
		if(!earlier) cluster.rootWalker(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> int log(Reference location, T instance, RefField<T, ?> field, Reference value, Holder holder){
				if(value.getPtr().equals(toMove)){
					found[0] = true;
					return END;
				}
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference location, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder){
				if(value.equals(toMove)){
					found[0] = true;
					return END;
				}
				return CONTINUE;
			}
		}, false).walk();
		if(!earlier && !found[0]) return new MoveBuffer();
		
		Chunk destination;
		try(var pin = source.io()){
			destination = earlier? manager.allocBefore(ticket, toMove) : ticket.submit(cluster);
			if(destination == null){
				return new MoveBuffer();
			}
			try{
				// Read only this physical body, never the chain beyond it.
				try(var input = cluster.getSource().ioAt(source.dataStart());
				    var output = cluster.getSource().ioAt(destination.dataStart())){
					
					byte[] buffer    = new byte[(int)Math.min(8192, source.getSize())];
					long   remaining = source.getSize();
					while(remaining>0){
						int count = (int)Math.min(buffer.length, remaining);
						input.readFully(buffer, 0, count);
						output.write(buffer, 0, count);
						remaining -= count;
					}
				}
				destination.setSize(source.getSize());
				destination.syncStruct();
			}catch(IOException|RuntimeException failure){
				try{
					manager.free(destination);
				}catch(IOException|RuntimeException cleanup){
					failure.addSuppressed(cleanup);
				}
				throw failure;
			}
		}
		
		rewriteReferences(cluster, toMove, destination.getPtr());
		manager.free(source);
		return new MoveBuffer(toMove, destination.getPtr());
	}
	
	private static void rewriteReferences(Cluster cluster, ChunkPointer from, ChunkPointer to) throws IOException{
		var record = new MemoryWalker.PointerRecord(){
			boolean updated;
			final Set<String> rewritten = new HashSet<>();
			@Override
			public <T extends IOInstance<T>> int log(Reference location, T instance, RefField<T, ?> field, Reference value, Holder holder) throws IOException{
				var ptr = value.getPtr();
				if(shouldBlockRewrite(location, ptr, field.getName())) return CONTINUE;
				field.setReference(instance, Reference.of(to, value.getOffset()));
				updated = true;
				return SAVE|END;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference location, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder) throws IOException{
				if(shouldBlockRewrite(location, value, field.getName())) return CONTINUE;
				updated = true;
				if(field.isReadOnly()){
					holder.value = to;
					return HOLDER_COPY|END;
				}
				field.set(null, instance, to);
				return SAVE|END;
			}
			private boolean shouldBlockRewrite(Reference location, ChunkPointer ptr, String name) throws IOException{
				if(!ptr.equals(from) && !ptr.equals(to)){
					return true;
				}
				var key = location + ":" + name;
				if(!rewritten.add(key)){
					if(ptr.equals(from)){
						throw new IOException("Reference rewrite did not persist at " + key);
					}
					return true;
				}
				return false;
			}
		};
		// Saving can change the containing object. Restart using persisted data;
		// every pass updates another alias, including raw and chain-next pointers.
		do{
			record.updated = false;
			cluster.rootWalker(record, false).walk();
		}while(record.updated);
	}
	
	private ChunkSet findFreeChunks(Cluster cluster) throws IOException{
		var unreferencedChunks = new ChunkSet();
		var knownFree          = new ChunkSet(cluster.getMemoryManager().getFreeChunks());
		var physicalFree       = new ChunkSet();
		for(var extent : new PhysicalExtentWalker(cluster, cluster.getFirstChunk().getPtr())){
			if(extent instanceof FreeChunk){
				physicalFree.add(extent.getPtr());
			}else{
				unreferencedChunks.add(extent.getPtr());
			}
		}
		// An orphan FreeChunk is a broken persistent chain, not an unreferenced
		// live allocation. Never repair it by interpreting its bytes as a Chunk.
		if(physicalFree.trueSize() != knownFree.trueSize() || !physicalFree.containsAll(knownFree)){
			throw new MalformedFile("Physical free extents disagree with the persistent free chain");
		}
		cluster.rootWalker(MemoryWalker.PointerRecord.of(ref -> {
			if(ref.isNull()) return;
			for(var chunk : ref.getPtr().dereference(cluster).collectNext()){
				unreferencedChunks.remove(chunk.getPtr());
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
	
}
