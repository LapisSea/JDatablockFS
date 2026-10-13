package com.lapissea.dfs.core;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkCache;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.core.memory.VerySimpleMemoryManager;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.exceptions.MalformedPointer;
import com.lapissea.dfs.exceptions.PointerOutsideFile;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOTypeDB;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public interface DataProvider{
	
	interface Holder{
		DataProvider getDataProvider();
	}
	
	class VerySimple implements DataProvider{
		private final IOTypeDB      typeDB        = new IOTypeDB.MemoryOnlyDB.Synchronized();
		private final ChunkCache    cache         = new ChunkCache();
		private final MemoryManager memoryManager = new VerySimpleMemoryManager(this);
		private final IOInterface   data;
		private final long          transactionVersion;
		
		public VerySimple(IOInterface data){
			this.data = data;
			transactionVersion = data.getTransactionVersion();
			data.getUnmanagedRegistry().registerProvider(this);
		}
		
		@Override
		public void checkValid(){ data.checkTransactionVersion(transactionVersion); }
		
		@Override
		public IOTypeDB getTypeDb(){
			checkValid();
			return typeDB;
		}
		@Override
		public IOInterface getSource(){
			checkValid();
			return data;
		}
		@Override
		public MemoryManager getMemoryManager(){
			checkValid();
			return memoryManager;
		}
		@Override
		public ChunkCache getChunkCache(){
			checkValid();
			return cache;
		}
		@Override
		public Chunk getFirstChunk() throws IOException{
			return getChunk(ChunkPointer.of(MagicID.size()));
		}
		@Override
		public Chunk walkToLastChunk() throws IOException{
			Chunk ch;
			try{
				ch = getFirstChunk();
			}catch(MalformedPointer e){
				return null;
			}
			while(true){
				var next = ch.nextPhysical();
				if(next == null) break;
				ch = next;
			}
			return ch;
		}
		@Override
		public String toString(){
			return "Very simple provider";
		}
	}
	
	static DataProvider newVerySimpleProvider(){
		return newVerySimpleProvider((IOHook)null);
	}
	
	static DataProvider newVerySimpleProvider(IOHook onWrite){
		var data = new MemoryData.Builder()
			           .withOnWrite(onWrite)
			           .withRaw(new byte[MagicID.size()])
			           .build();
		return newVerySimpleProvider(data);
	}
	
	static DataProvider newVerySimpleProvider(IOInterface data){
		return new VerySimple(data);
	}
	
	/**
	 * Fails after an unrecoverable physical commit or state restoration error
	 */
	default void checkValid(){ }
	
	default UnmanagedRegistry getUnmanagedRegistry(){ return getSource().getUnmanagedRegistry(); }
	
	/**
	 * Restore provider-owned caches after bytes and unmanaged identities have been restored
	 */
	default void refreshAfterRollback() throws IOException{ }
	
	IOTypeDB getTypeDb();
	
	IOInterface getSource();
	MemoryManager getMemoryManager();
	ChunkCache getChunkCache();
	
	Chunk getFirstChunk() throws IOException;
	
	Chunk walkToLastChunk() throws IOException;
	
	default Chunk getChunkCached(ChunkPointer ptr){
		Objects.requireNonNull(ptr);
		return getChunkCache().get(ptr);
	}
	default Chunk getChunk(ChunkPointer ptr) throws IOException{
		try(var ioAccess = getSource().lockIO()){
			checkValid();
			ptr.requireNonNull();
			if(DEBUG_VALIDATION){
				ensureChunkValid(ptr);
			}
			var cc = getChunkCache();
			synchronized(cc){
				var ch = cc.get(ptr);
				if(ch != null) return ch;
				
				var read = readChunk(ptr);
				Objects.requireNonNull(read);
				cc.add(read);
				return read;
			}
		}
	}
	
	private void ensureChunkValid(ChunkPointer ptr) throws IOException{
		var siz = getSource().getIOSize();
		if(ptr.getValue()>=siz){
			throw new PointerOutsideFile("Pointer outside " + siz + " file: ", ptr);
		}
		
		var cached = getChunkCache().get(ptr);
		if(cached == null) return;
		var read = readChunk(ptr);
		if(!read.equals(cached)){
			throw new CacheOutOfSync(read, cached);
		}
	}
	
	private Chunk readChunk(ChunkPointer ptr) throws IOException{
		return Chunk.readChunk(this, ptr);
	}
	
	default boolean isReadOnly(){
		return getSource().isReadOnly();
	}
	
	default boolean isLastPhysical(Chunk chunk) throws IOException{
		return chunk.dataEnd()>=getSource().getIOSize();
	}
	default void validate(){ }
	
	default void checkCached(Chunk chunk){
		Chunk cached = getChunkCached(chunk.getPtr());
		if(cached != chunk){
			throw new IllegalStateException("Fake " + chunk);
		}
	}
	
	default DataProvider withRouter(Function<AllocateTicket, AllocateTicket> router){
		class Routed implements DataProvider, MemoryManager{
			@Override
			public void checkValid(){ DataProvider.this.checkValid(); }
			
			private MemoryManager src(){
				return DataProvider.this.getMemoryManager();
			}
			
			@Override
			public IOTypeDB getTypeDb(){ return DataProvider.this.getTypeDb(); }
			@Override
			public IOInterface getSource(){ return DataProvider.this.getSource(); }
			@Override
			public MemoryManager getMemoryManager(){ return this; }
			@Override
			public ChunkCache getChunkCache(){ return DataProvider.this.getChunkCache(); }
			@Override
			public Chunk getFirstChunk() throws IOException{ return DataProvider.this.getFirstChunk(); }
			@Override
			public Chunk walkToLastChunk() throws IOException{ return DataProvider.this.walkToLastChunk(); }
			@Override
			public void refreshAfterRollback() throws IOException{
				DataProvider.this.refreshAfterRollback();
			}
			@Override
			public DefragSes openDefragmentMode(){ return src().openDefragmentMode(); }
			@Override
			public IOList<ChunkPointer> getFreeChunks(){ return src().getFreeChunks(); }
			@Override
			public DataProvider getDataProvider(){ return this; }
			@Override
			public void free(Collection<Chunk> toFree) throws IOException{
				src().free(toFree);
			}
			@Override
			public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
				var src = src();
				src.allocTo(firstChunk, target, toAllocate);
			}
			
			@Override
			public Chunk alloc(AllocateTicket ticket) throws IOException{
				return src().alloc(router.apply(ticket));
			}
			@Override
			public void notifyStart(ChunkChainIO chain){ src().notifyStart(chain); }
			@Override
			public void notifyEnd(ChunkChainIO chain){ src().notifyEnd(chain); }
			@Override
			public boolean canAlloc(AllocateTicket ticket) throws IOException{
				return src().canAlloc(router.apply(ticket));
			}
			@Override
			public String toString(){
				return DataProvider.this.toString();
			}
			
			@Override
			public DataProvider withRouter(Function<AllocateTicket, AllocateTicket> router){
				return DataProvider.this.withRouter(router);
			}
		}
		
		return new Routed();
	}
}
