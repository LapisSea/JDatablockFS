package com.lapissea.cfs.chunk;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.MagicID;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.AbstractUnmanagedIOMap;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.cfs.type.field.annotations.IOValue.Reference.PipeType.FLEXIBLE;

public class Cluster implements DataProvider{
	
	public static final  FixedContiguousStructPipe<RootRef> ROOT_PIPE      =FixedContiguousStructPipe.of(RootRef.class);
	private static final ChunkPointer                       FIRST_CHUNK_PTR=ChunkPointer.of(MagicID.size());
	
	public static Cluster emptyMem() throws IOException{
		return Cluster.init(MemoryData.builder().build());
	}
	
	public static Cluster init(IOInterface data) throws IOException{
		data.openIOTransaction(()->{
			var provider=DataProvider.newVerySimpleProvider(data);
			
			try(var io=data.write(true)){
				MagicID.write(io);
			}
			
			var firstChunk=AllocateTicket.withData(ROOT_PIPE, provider, new RootRef())
			                             .withApproval(c->c.getPtr().equals(FIRST_CHUNK_PTR))
			                             .submit(provider);
			
			var db=new IOTypeDB.PersistentDB();
			db.init(provider);
			
			ROOT_PIPE.modify(firstChunk, root->{
				Metadata metadata=root.metadata;
				metadata.db=db;
				metadata.allocateNulls(provider);
			}, null);
		});
		
		return new Cluster(data);
	}
	
	
	public static class RootRef extends IOInstance.Managed<RootRef>{
		@IOValue
		@IOValue.Reference(dataPipeType=FLEXIBLE)
		@IONullability(DEFAULT_IF_NULL)
		private Metadata metadata;
	}
	
	@IOInstance.Def.ToString(name=false, curly=false, fNames=false)
	private interface IOChunkPointer extends IOInstance.Def<IOChunkPointer>{
		ChunkPointer getVal();
	}
	
	private static class Metadata extends IOInstance.Managed<Metadata>{
		
		@IOValue
		@IONullability(NULLABLE)
		@IOValue.OverrideType(value=HashIOMap.class)
		private AbstractUnmanagedIOMap<ObjectID, Object> rootObjects;
		
		@IOValue
		@IONullability(NULLABLE)
		private IOTypeDB.PersistentDB db;
		
		@IOValue
		@IONullability(NULLABLE)
		private IOList<IOChunkPointer> freeChunks;
	}
	
	private final ChunkCache chunkCache=ChunkCache.strong();
	
	private final IOInterface       source;
	private final MemoryManager     memoryManager;
	private final DefragmentManager defragmentManager=new DefragmentManager(this);
	
	private final RootRef root;
	
	
	private final RootProvider rootProvider=new RootProvider(){
		@SuppressWarnings("unchecked")
		@Override
		public <T> T request(ObjectID id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException{
			Objects.requireNonNull(id);
			Objects.requireNonNull(objectGenerator);
			
			var meta=meta();
			
			var existing=meta.rootObjects.get(id);
			if(existing!=null){
				return (T)existing;
			}
			
			var inst=objectGenerator.get();
			
			meta.rootObjects.put(id, inst);
			return inst;
		}
		
		@Override
		public <T> void provide(ObjectID id, T obj) throws IOException{
			Objects.requireNonNull(obj);
			var meta=meta();
			meta.rootObjects.put(id, obj);
		}
		
		@Override
		public DataProvider getDataProvider(){
			return Cluster.this;
		}
		
		@Override
		public IterablePP<IOMap.IOEntry<ObjectID, Object>> listAll(){
			return ()->meta().rootObjects.iterator();
		}
		
		@Override
		public void drop(ObjectID id) throws IOException{
			meta().rootObjects.remove(id);
		}
	};
	
	public Cluster(IOInterface source) throws IOException{
		this.source=source;
		
		source.read(MagicID::read);
		
		Chunk ch=getFirstChunk();
		
		var s=ROOT_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		if(s>ch.getSize()){
			throw new IOException("no valid cluster data "+s+" "+ch.getSize());
		}
		
		root=ROOT_PIPE.readNew(this, ch, null);
		var frees =meta().freeChunks;
		var mapped=frees.map(IOChunkPointer::getVal, IOInstance.Def.constrRef(IOChunkPointer.class, ChunkPointer.class));
		memoryManager=new PersistentMemoryManager(this, mapped);
	}
	
	@Override
	public Chunk getFirstChunk() throws IOException{
		try{
			return getChunk(FIRST_CHUNK_PTR);
		}catch(MalformedPointerException e){
			throw new IOException("First chunk does not exist", e);
		}
	}
	
	private Metadata meta(){
		return root.metadata;
	}
	
	@Override
	public IOTypeDB getTypeDb(){
		if(root==null) return null;
		return meta().db;
	}
	
	@Override
	public IOInterface getSource(){
		return source;
	}
	@Override
	public MemoryManager getMemoryManager(){
		return Objects.requireNonNull(memoryManager);
	}
	public RootProvider getRootProvider(){
		return rootProvider;
	}
	
	@Override
	public ChunkCache getChunkCache(){
		return chunkCache;
	}
	
	@Override
	public String toString(){
		return "Cluster{"+
		       "source="+source+
		       '}';
	}
	
	
	public record ChunkStatistics(
		long totalBytes,
		long chunkCount,
		double usefulDataRatio,
		double chunkFragmentation,
		double usedChunkEfficiency
	){}
	
	public ChunkStatistics gatherStatistics() throws IOException{
		
		long totalBytes       =getSource().getIOSize();
		long usedChunkCapacity=0;
		long usefulBytes      =0;
		long chunkCount       =0;
		long hasNextCount     =0;
		
		Set<ChunkPointer> referenced=new HashSet<>();
		
		rootWalker(MemoryWalker.PointerRecord.of(ref->{
			if(ref.isNull()) return;
			ref.getPtr().dereference(this).streamNext().map(Chunk::getPtr).forEach(referenced::add);
		}), true).walk();
		
		for(Chunk chunk : getFirstChunk().chunksAhead()){
			if(referenced.contains(chunk.getPtr())){
				usefulBytes+=chunk.getSize();
				usedChunkCapacity+=chunk.getCapacity();
			}
			chunkCount++;
			if(chunk.hasNextPtr()) hasNextCount++;
		}
		
		return new ChunkStatistics(
			totalBytes,
			chunkCount,
			usefulBytes/(double)totalBytes,
			hasNextCount/(double)chunkCount,
			usefulBytes/(double)usedChunkCapacity
		);
	}
	
	public MemoryWalker rootWalker(MemoryWalker.PointerRecord rec, boolean refRoot) throws IOException{
		return rootWalker(rec, refRoot, false);
	}
	public MemoryWalker rootWalker(MemoryWalker.PointerRecord rec, boolean refRoot, boolean recordStats) throws IOException{
		if(refRoot){
			rec.log(new Reference(), null, null, FIRST_CHUNK_PTR.makeReference());
		}
		return new MemoryWalker(this, root, getFirstChunk().getPtr().makeReference(), Cluster.ROOT_PIPE, recordStats, rec);
	}
	
	public void defragment() throws IOException{
		defragmentManager.defragment();
	}
}
