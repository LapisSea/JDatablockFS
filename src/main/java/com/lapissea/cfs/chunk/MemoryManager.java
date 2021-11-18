package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public interface MemoryManager{
	
	/**
	 * Partial implementation of {@link MemoryManager} that reduces boilerplate for allocation and bookkeeping
	 */
	abstract class StrategyImpl implements MemoryManager{
		
		public interface AllocStrategy{
			/**
			 * @return a chunk that has been newly allocated from context. Returned chunk should always have a size of 0,
			 * capacity greater or equal to the ticket request. (optimally equal capacity but greater is also fine)
			 */
			Chunk alloc(@NotNull DataProvider context, @NotNull AllocateTicket ticket) throws IOException;
		}
		
		public interface AllocToStrategy{
			/**
			 * @param firstChunk is the chunk that is at the start of the chain.
			 * @param target     is the last chunk in the chain. It is the chunk that will be modified to achieve extra capacity in the chain.
			 * @param toAllocate is the number of bytes that would need to be allocated. This is only a suggestion but should be taken seriously.
			 * @return amount of bytes that has been newly allocated to target. (directly or indirectly) Returning 0 indicates a failure
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
			this.context=context;
			this.allocs=List.copyOf(createAllocs());
			this.allocTos=List.copyOf(createAllocTos());
		}
		
		protected abstract List<AllocStrategy> createAllocs();
		protected abstract List<AllocToStrategy> createAllocTos();
		
		@Override
		public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
			
			MemoryOperations.checkValidityOfChainAlloc(context, firstChunk, target);
			
			var last=target;
			
			long remaining=toAllocate;
			strategyLoop:
			while(remaining>0){
				while(last.hasNextPtr()){
					last=last.next();
				}
				
				for(AllocToStrategy allocTo : allocTos){
					long allocated=allocTo.allocTo(firstChunk, last, remaining);
					
					if(DEBUG_VALIDATION){
						assert !last.dirty();
						if(allocated<0){
							throw new IllegalStateException();
						}
					}
					
					remaining-=allocated;
					if(allocated>0){
						continue strategyLoop;
					}
				}
				
				throw new NotImplementedException("Tried to allocate "+toAllocate+" bytes to "+last.getPtr()+" but there is no known way to do that");
			}
			
		}
		
		@Override
		public Chunk alloc(AllocateTicket ticket) throws IOException{
			
			Chunk chunk;
			
			tryStrategies:
			{
				for(AllocStrategy alloc : allocs){
					chunk=alloc.alloc(context, ticket);
					if(chunk!=null) break tryStrategies;
				}
				throw new NotImplementedException("Tried to allocate with "+ticket+" but there is no known way to do that");
			}
			
			context.getChunkCache().add(chunk);
			
			var initial=ticket.dataPopulator();
			if(initial!=null){
				initial.accept(chunk);
			}
			
			return chunk;
		}
	}
	
	IOList<ChunkPointer> getFreeChunks();
	
	default void free(Chunk toFree) throws IOException{
		free(List.of(toFree));
	}
	void free(Collection<Chunk> toFree) throws IOException;
	
	void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException;
	
	Chunk alloc(AllocateTicket ticket) throws IOException;
}
