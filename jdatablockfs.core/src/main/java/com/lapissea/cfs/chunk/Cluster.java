package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.InvalidMagicIDException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.collections.AbstractUnmanagedIOMap;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.cfs.type.field.annotations.IOValue.Reference.PipeType.FIXED;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Cluster implements DataProvider{
	
	private static final ByteBuffer MAGIC_ID=ByteBuffer.wrap("BYT-BAE".getBytes(UTF_8)).asReadOnlyBuffer();
	
	public static final  StructPipe<RootRef> ROOT_PIPE;
	private static final ChunkPointer        FIRST_CHUNK_PTR;
	
	static{
		try{
			ROOT_PIPE=FixedContiguousStructPipe.of(RootRef.class);
			FIRST_CHUNK_PTR=ChunkPointer.of(MAGIC_ID.limit());
		}catch(Throwable e){
			e.printStackTrace();
			throw e;
		}
	}
	
	public static ByteBuffer getMagicId(){
		return MAGIC_ID.asReadOnlyBuffer();
	}
	
	private static void readMagic(ContentReader src) throws InvalidMagicIDException{
		ByteBuffer magicId;
		try{
			magicId=ByteBuffer.wrap(src.readInts1(MAGIC_ID.limit()));
			if(!magicId.equals(MAGIC_ID)){
				throw new InvalidMagicIDException("ID: "+UTF_8.decode(magicId));
			}
		}catch(IOException e){
			throw new InvalidMagicIDException("There is no valid magic id, was Cluster.init called?", e);
		}
	}
	
	public static Cluster init(IOInterface data) throws IOException{
		data.openIOTransaction(()->{
			var provider=DataProvider.newVerySimpleProvider(data);
			
			try(var io=data.write(true)){
				io.write(MAGIC_ID);
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
	
	
	public static class RootRef extends IOInstance<RootRef>{
		@IOValue
		@IOValue.Reference(dataPipeType=FIXED)
		@IONullability(DEFAULT_IF_NULL)
		private Metadata metadata;
	}
	
	private static class IOChunkPointer extends IOInstance<IOChunkPointer>{
		
		@IOValue
		private ChunkPointer val=ChunkPointer.NULL;
		
		private IOChunkPointer(){}
		private IOChunkPointer(ChunkPointer val){
			this.val=val;
		}
		
		private ChunkPointer getVal(){
			return val;
		}
	}
	
	private static class Metadata extends IOInstance<Metadata>{
		
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
		public <T> void provide(T obj, ObjectID id) throws IOException{
			Objects.requireNonNull(obj);
			var meta=meta();
			meta.rootObjects.put(id, obj);
		}
		
		@Override
		public DataProvider getDataProvider(){
			return Cluster.this;
		}
	};
	
	public Cluster(IOInterface source) throws IOException{
		this.source=source;
		
		try(var io=source.read()){
			readMagic(io);
		}
		
		Chunk ch=getFirstChunk();
		var   s =ROOT_PIPE.getSizeDescriptor().fixedOrMin(WordSpace.BYTE);
		if(s>ch.getSize()){
			throw new IOException("no valid cluster data "+s+" "+ch.getSize()+" "+ch.io().getSize());
		}
		root=readRootRef();
		memoryManager=new PersistentMemoryManager(this, meta().freeChunks.map(IOChunkPointer::getVal, IOChunkPointer::new));
	}
	
	private RootRef readRootRef() throws IOException{
		return ROOT_PIPE.readNew(this, getFirstChunk(), null);
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
		return memoryManager;
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
		
		rootWalker().walk(true, ref->{
			if(!ref.isNull()){
				try{
					for(Chunk chunk : new ChainWalker(ref.getPtr().dereference(this))){
						referenced.add(chunk.getPtr());
					}
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		});
		
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
	
	public MemoryWalker rootWalker() throws IOException{
		return new MemoryWalker(this, root, getFirstChunk().getPtr().makeReference(), Cluster.ROOT_PIPE);
	}
	
	public void defragment() throws IOException{
		defragmentManager.defragment();
	}
}