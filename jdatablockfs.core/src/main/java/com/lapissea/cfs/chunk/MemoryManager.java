package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.UnknownAllocationMethod;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;

/**
 * This interface handles the management of memory (duh). This includes allocation of new independent {@link Chunk}s or
 * can extend a chunk by allocation extra capacity to it or its children (next chunk(s)) and on the other side, it can
 * provide a way to handle free/unused data by noting the free chunks down and shredding any sensitive garbage data.<br>
 * Sensitive garbage data is defined as any data that can be accessed and read easily. This in practice means any sequence of
 * bytes that can be interpreted as a valid chunk header.
 */
public interface MemoryManager extends DataProvider.Holder{
	
	interface MoveInfo{
		void start(ChunkChainIO chain);
		void end(ChunkChainIO chain);
	}
	
	interface DefragSes extends AutoCloseable{
		@Override
		void close();
	}
	
	/**
	 * Partial implementation of {@link MemoryManager} that reduces boilerplate for allocation and bookkeeping
	 */
	abstract class StrategyImpl implements MemoryManager{
		
		public interface AllocStrategy{
			/**
			 * @return a chunk that has been newly allocated from context. Returned chunk should always have a size of 0,
			 * capacity greater or equal to the ticket request. (optimally equal capacity but greater is also fine) If
			 * null, the strategy signals that it has failed.
			 */
			Chunk alloc(@NotNull DataProvider context, @NotNull AllocateTicket ticket) throws IOException;
		}
		
		public interface AllocToStrategy{
			/**
			 * @param firstChunk is the chunk that is at the start of the chain.
			 * @param target     is the last chunk in the chain. It is the chunk that will be modified to achieve extra capacity in the chain.
			 * @param toAllocate is the number of bytes that would need to be allocated. (should be greater than 0)
			 *                   This is only a suggestion but should be taken seriously.
			 * @return the number of bytes that have been newly allocated to target (directly or indirectly). Returning 0 indicates a failure
			 * of the strategy. Returning any other positive number signifies a success. The amount of bytes allocates should try to be as close
			 * as possible to toAllocate. If it is less than, another pass of strategy executing will be done. If greater or equal, then the
			 * allocation sequence will end.
			 */
			long allocTo(@NotNull Chunk firstChunk, @NotNull Chunk target, long toAllocate) throws IOException;
		}
		
		protected final DataProvider          context;
		private final   List<AllocStrategy>   allocs;
		private final   List<AllocToStrategy> allocTos;
		
		public StrategyImpl(DataProvider context){
			this.context = context;
			this.allocs = List.copyOf(createAllocs());
			this.allocTos = List.copyOf(createAllocTos());
		}
		
		protected abstract List<AllocStrategy> createAllocs();
		protected abstract List<AllocToStrategy> createAllocTos();
		
		@Override
		public DataProvider getDataProvider(){
			return context;
		}
		
		@Override
		public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
			Objects.requireNonNull(firstChunk);
			Objects.requireNonNull(target);
			if(toAllocate<0){
				throw new IllegalArgumentException();
			}
			
			if(DEBUG_VALIDATION) MemoryOperations.checkValidityOfChainAlloc(context, firstChunk, target);
			
			var last = target;
			
			long remaining = toAllocate;
			strategyLoop:
			while(remaining>0){
				last = last.last();
				
				for(AllocToStrategy allocTo : allocTos){
					long allocated = allocTo.allocTo(firstChunk, last, remaining);
					
					if(DEBUG_VALIDATION){
						checkChainData(firstChunk);
						
						if(last.dirty()) throw new RuntimeException(last + " is dirty");
						if(allocated<0){
							throw new IllegalStateException();
						}
					}
					if(allocated == 0) continue;
					
					remaining -= allocated;
					if(allocated>0){
						continue strategyLoop;
					}
				}
				
				throw new UnknownAllocationMethod("Tried to allocate " + toAllocate + " bytes to " + last.getPtr() + " but there is no known way to do that");
			}
			
		}
		
		private void checkChainData(Chunk firstChunk) throws IOException{
			var ch = firstChunk;
			while(ch != null){
				var n = ch.next();
				if(n != null && n.getSize()>0){
					if(ch.getCapacity() != ch.getSize()){
						throw new IllegalStateException(ch + " is not full but has next with data");
					}
				}
				ch = n;
			}
		}
		
		@Override
		public Chunk alloc(AllocateTicket ticket) throws IOException{
			long minSize = minAllocationCapacity();
			if(ticket.bytes()<minSize){
				ticket = ticket.withBytes(minSize);
			}
			
			Chunk chunk;
			
			tryStrategies:
			{
				for(var alloc : allocs){
					chunk = alloc.alloc(context, ticket);
					if(chunk != null){
						if(DEBUG_VALIDATION) postAllocValidate(ticket, chunk);
						break tryStrategies;
					}
				}
				return null;
			}
			
			
			var initial = ticket.dataPopulator();
			if(initial != null){
				initial.accept(chunk);
			}
			
			return chunk;
		}
		
		private static void postAllocValidate(AllocateTicket ticket, Chunk chunk) throws IOException{
			chunk.requireReal();
			var nsizO = ticket.explicitNextSize();
			if(nsizO.isPresent()){
				var nsiz  = nsizO.get();
				var chSiz = chunk.getNextSize();
				if(nsiz.greaterThan(chSiz)){
					throw new IllegalStateException("Allocation did not respect explicit next size since " + nsiz + " > " + chSiz);
				}
			}
		}
	}
	
	MoveInfo getMoveInfo();
	
	DefragSes openDefragmentMode();
	
	/**
	 * Lists locations of all KNOWN chunks in a sorted order from smallest to biggest. This may not be a complete list of unused chunks.
	 */
	IOList<ChunkPointer> getFreeChunks();
	
	/**
	 * Takes any number of {@link ChunkPointer}s as input and collects all the next chunks
	 * for each pointer in the collection. It then frees all the collected chunks
	 */
	default void freeChains(Collection<ChunkPointer> chainStarts) throws IOException{
		if(chainStarts.isEmpty()) return;
		List<Chunk> chunks = chunksToChains(chainStarts);
		free(chunks);
	}
	private List<Chunk> chunksToChains(Collection<ChunkPointer> chainStarts) throws IOException{
		List<Chunk> chunks = new ArrayList<>(chainStarts.size());
		for(var ptr : chainStarts){
			if(DEBUG_VALIDATION){
				if(chunks.stream().anyMatch(c -> c.getPtr().equals(ptr))){
					throw new RuntimeException("Duplicate pointer passed " + ptr);
				}
			}
			
			ptr.dereference(getDataProvider()).addChainTo(chunks);
		}
		return chunks;
	}
	
	
	/**
	 * Explicitly frees a chunk.<br>
	 * <br>
	 * This may alter the chunk contents, properties or completely destroy it. Do not use this chunk or any object that could
	 * use it after its freed! Any data that was, is assumed to be gone permanently and will never be accessible through normal means.
	 * Usage of this chunk after it has been freed can cause crashes or serious data corruption.
	 */
	default void free(Chunk toFree) throws IOException{
		free(List.of(toFree));
	}
	/**
	 * Explicitly frees a collection of chunks.<br>
	 * It is preferable (within reason) to free as many chunks at once. The manager can more efficiently handle them if they are presented at once.<br>
	 * <br>
	 * This may alter any/all the chunk contents, properties or completely destroy them. Do not use this chunk or any object that could
	 * use them after they are freed! Any data that was, is assumed to be gone permanently and will never be accessible through normal means.
	 * Usage of any chunks after they have been freed can cause crashes or serious data corruption.
	 */
	void free(Collection<Chunk> toFree) throws IOException;
	
	/**
	 * Allocates additional capacity to the target chunk. The additional capacity may be equal to the toAllocate parm but can also be greater.
	 *
	 * @param firstChunk the first chunk in the chain. May be used to reallocate the complete chain and modify all references to it.
	 * @param target     the last chunk in the chain. Will most commonly be modified to achieve extra capacity.
	 * @param toAllocate Minimum number of additional bytes to allocate to the capacity
	 */
	void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException;
	
	/**
	 * Allocates a new independent chunk, unreferenced by anything. All instructions on what and how to allocate it are provided in the ticket.
	 */
	Chunk alloc(AllocateTicket ticket) throws IOException;
	
	default long minAllocationCapacity(){
		return 1;
	}
}
