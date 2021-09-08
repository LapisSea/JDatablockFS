package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.InvalidMagicIDException;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.GenericContainer;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.StructLayout;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;
import static java.nio.charset.StandardCharsets.*;

public class Cluster implements ChunkDataProvider{
	
	private static final ByteBuffer MAGIC_ID=ByteBuffer.wrap("BYT-BAE".getBytes(UTF_8)).asReadOnlyBuffer();
	
	private static final StructPipe<RootRef> ROOT_PIPE      =FixedContiguousStructPipe.of(RootRef.class);
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
		
		var provider=ChunkDataProvider.newVerySimpleProvider(data);
		
		Chunk firstChunk;
		try(var io=data.write(true)){
			io.write(MAGIC_ID);
		}
		firstChunk=AllocateTicket.withData(ROOT_PIPE, new RootRef())
		                         .withApproval(c->c.getPtr().equals(FIRST_CHUNK_PTR))
		                         .submit(provider);
		
		ROOT_PIPE.modify(firstChunk, root->{
			Metadata metadata=root.metadata;
			metadata.allocateNulls(provider);
		});
	}
	
	
	public static class RootRef extends IOInstance<RootRef>{
		
		@IOValue
		@IOValue.Reference
		@IONullability(DEFAULT_IF_NULL)
		private Metadata metadata;
		
		public RootRef(){ }
		RootRef(Metadata metadata){
			this.metadata=metadata;
		}
		
		public Metadata getMetadata(){
			return metadata;
		}
	}
	
	
	private static class Metadata extends IOInstance<Metadata>{
		
		@IOValue
		@IONullability(NULLABLE)
		@IOValue.OverrideType(value=ContiguousIOList.class)
		public IOList<GenericContainer<?>> rootReferences;
		
		@IOValue
		@IONullability(NULLABLE)
		public AutoText text;
		
		@IOValue
		@IONullability(NULLABLE)
		@IOValue.OverrideType(value=ContiguousIOList.class)
		public List<StructLayout> types;
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
		if(ROOT_PIPE.getSizeDescriptor().fixedOrMin()>ch.getSize()){
			throw new IOException("no valid cluster data");
		}
		root=ROOT_PIPE.readNew(this, ch);
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
	
	public IOList<GenericContainer<?>> getRootReferences(){
		return root.getMetadata().rootReferences;
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
}
