package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.InvalidMagicIDException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.cfs.type.field.annotations.IOValue.Reference.PipeType.FIXED;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Cluster implements DataProvider{
	
	private static final ByteBuffer MAGIC_ID=ByteBuffer.wrap("BYT-BAE".getBytes(UTF_8)).asReadOnlyBuffer();
	
	public static final  StructPipe<RootRef> ROOT_PIPE      =FixedContiguousStructPipe.of(RootRef.class);
	private static final ChunkPointer        FIRST_CHUNK_PTR=ChunkPointer.of(MAGIC_ID.limit());
	
	public static ByteBuffer getMagicId(){
		return MAGIC_ID.asReadOnlyBuffer();
	}
	
	private static void readMagic(ContentReader src) throws InvalidMagicIDException{
		ByteBuffer magicId;
		try{
			magicId=ByteBuffer.wrap(src.readInts1(MAGIC_ID.limit()));
		}catch(IOException e){
			throw new InvalidMagicIDException("There is no magic id, was data initialized?");
		}
		if(!magicId.equals(MAGIC_ID)){
			throw new InvalidMagicIDException(UTF_8.decode(magicId)+" is not a valid magic id");
		}
	}
	
	public static void init(IOInterface data) throws IOException{
		
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
	}
	
	
	public static class RootRef extends IOInstance<RootRef>{
		@IOValue
		@IOValue.Reference(dataPipeType=FIXED)
		@IONullability(DEFAULT_IF_NULL)
		private Metadata metadata;
	}
	
	
	private static class Metadata extends IOInstance<Metadata>{
		
		@IOValue
		@IONullability(NULLABLE)
		@IOValue.OverrideType(value=HashIOMap.class)
		public IOMap<Object, Object> temp;
		
		@IOValue
		@IONullability(NULLABLE)
		private IOTypeDB.PersistentDB db;
	}
	
	private final ChunkCache chunkCache=ChunkCache.weak();
	
	private final IOInterface   source;
	private final MemoryManager memoryManager=new VerySimpleMemoryManager(this);
	
	private final RootRef root;
	
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
	
	public RootRef getRoot(){
		return root;
	}
	
	private Metadata meta(){
		return getRoot().metadata;
	}
	
	public IOMap<Object, Object> getTemp(){
		return meta().temp;
	}
	
	@Override
	public IOTypeDB getTypeDb(){
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
	
	
	public static record ChunkStatistics(
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
		
		for(Chunk chunk : new PhysicalChunkWalker(getFirstChunk())){
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
		return new MemoryWalker(this, getRoot(), getFirstChunk().getPtr().makeReference(), Cluster.ROOT_PIPE);
	}
	
	public void defragment() throws IOException{
		LogUtil.println("Defragmenting...");
		
		reallocateUnmanaged((HashIOMap<?, ?>)getTemp());
		
		tmp_realocMetadata();
	}
	
	private void tmp_realocMetadata() throws IOException{
		
		var refF  =root.getThisStruct().getFields().requireExact(Reference.class, "metadata.ref");
		var oldRef=refF.get(root);
		
		var pipeType=FixedContiguousStructPipe.class;
		
		var pip=(StructPipe<Metadata>)StructPipe.of(pipeType, root.metadata.getThisStruct());
		
		var siz=pip.getSizeDescriptor().calcUnknown(this, root.metadata);
		
		var newCh=AllocateTicket.bytes(siz).withDataPopulated((p, io)->{
			try(var src=oldRef.io(p)){
				src.transferTo(io);
			}
		}).submit(this);
		
		var newRef=newCh.getPtr().makeReference();
		
		moveReference(oldRef, newRef);
	}
	
	private <T extends IOInstance.Unmanaged<T>> void reallocateUnmanaged(T instance) throws IOException{
		var oldRef=instance.getReference();
		var pip   =instance.getPipe();
		
		var siz=pip.getSizeDescriptor().calcUnknown(this, instance, WordSpace.BYTE);
		
		var newCh=AllocateTicket.bytes(siz).withDataPopulated((p, io)->{
			oldRef.withContext(p).io(src->src.transferTo(io));
		}).submit(this);
		var newRef=newCh.getPtr().makeReference();
		
		var ptrsToFree=moveReference(oldRef, newRef);
		instance.notifyReferenceMovement(newRef);
		getMemoryManager().freeChains(ptrsToFree);
	}
	
	private Set<ChunkPointer> moveReference(Reference oldRef, Reference newRef) throws IOException{
		boolean[]         found ={false};
		Set<ChunkPointer> toFree=new HashSet<>();
		rootWalker().walk(new MemoryWalker.PointerRecord(){
			boolean foundCh;
			@Override
			public <T extends IOInstance<T>> boolean log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
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
					try(var io=instanceReference.io(Cluster.this)){
						pipe.write(Cluster.this, io, instance);
					}
					found[0]=true;
					return false;
				}
				return true;
			}
			@Override
			public <T extends IOInstance<T>> boolean logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
				return true;
			}
		});
		if(!found[0]){
			throw new IOException("Failed to find "+oldRef);
		}
		return toFree;
	}
	
}
