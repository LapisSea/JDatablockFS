package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.MalformedFileException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.lapissea.cfs.objects.NumberSize.*;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.*;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;
import static java.nio.charset.StandardCharsets.*;

public class Cluster implements ChunkDataProvider{
	
	static{
		Chunk.getPtr(null);
	}
	
	private static final ByteBuffer MAGIC_ID=ByteBuffer.wrap("BYTE-BABE".getBytes(UTF_8)).asReadOnlyBuffer();
	
	private static final NumberSize           FIRST_CHUNK_PTR_SIZE=LARGEST;
	private static final ChunkPointer         FIRST_CHUNK_PTR     =ChunkPointer.of(MAGIC_ID.limit()+FIRST_CHUNK_PTR_SIZE.bytes);
	private static final StructPipe<Metadata> METADATA_PIPE       =FixedContiguousStructPipe.of(Metadata.class);
	
	public static ByteBuffer getMagicId(){
		return MAGIC_ID.asReadOnlyBuffer();
	}
	
	private static void readMagic(ContentReader src) throws IOException{
		var magicId=ByteBuffer.wrap(src.readInts1(MAGIC_ID.limit()));
		if(!magicId.equals(MAGIC_ID)){
			throw new MalformedFileException(UTF_8.decode(magicId)+" is not a valid magic id");
		}
	}
	
	public static void init(IOInterface data) throws IOException{
		
		try(var io=data.write(true)){
			io.write(MAGIC_ID);
			FIRST_CHUNK_PTR_SIZE.write(io, FIRST_CHUNK_PTR);
		}
		
		var provider=ChunkDataProvider.newVerySimpleProvider(data);
		
		Metadata metadata=new Metadata();
		metadata.value1=300;
		
		Chunk ch=AllocateTicket.bytes(METADATA_PIPE.getSizeDescriptor().requireFixed())
		                       .withApproval(c->c.getPtr().equals(FIRST_CHUNK_PTR))
		                       .submit(provider);
		
		metadata.allocateNulls(provider);
		METADATA_PIPE.write(ch, metadata);
		metadata.list.add(new Dummy(4124));
		LogUtil.println(TextUtil.toNamedPrettyJson(metadata));
	}
	
	static class Dummy extends IOInstance<Dummy>{
		
		@IOValue
		int dummyValue;
		
		public Dummy(){ }
		public Dummy(int dummyValue){
			this.dummyValue=dummyValue;
		}
	}
	
	
	private static class Metadata extends IOInstance<Metadata>{
		
		@IOValue
		@IODependency.VirtualNumSize(name="ayyyy", retention=GROW_ONLY)
		public int value1=255;
		@IOValue
		@IODependency.VirtualNumSize(name="ayyyy", retention=GROW_ONLY)
		public int value2=69;
		
		@IOValue
		@IOValue.OverrideType(value=ContiguousIOList.class)
		public IOList<Dummy> list;
	}
	
	private final ChunkCache chunkCache=ChunkCache.weak();
	
	private final IOInterface   source;
	private final MemoryManager memoryManager=new VerySimpleMemoryManager(this);
	
	private final Metadata metadata;
	
	public Cluster(IOInterface source) throws IOException{
		this.source=source;
		
		ChunkPointer mainChunkPtr;
		try(var io=source.read()){
			readMagic(io);
			mainChunkPtr=ChunkPointer.read(FIRST_CHUNK_PTR_SIZE, io);
		}
		
		metadata=METADATA_PIPE.readNew(this, mainChunkPtr.dereference(this));
		
		LogUtil.println(TextUtil.toNamedPrettyJson(metadata));
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
