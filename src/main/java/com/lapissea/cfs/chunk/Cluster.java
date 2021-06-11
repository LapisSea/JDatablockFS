package com.lapissea.cfs.chunk;

import com.lapissea.cfs.exceptions.MalformedFileException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.lapissea.cfs.objects.NumberSize.*;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.*;
import static java.nio.charset.StandardCharsets.*;

public class Cluster implements ChunkDataProvider{
	
	private static final ByteBuffer MAGIC_ID=ByteBuffer.wrap("CC.DB/FS".getBytes(UTF_8));
	
	private static final NumberSize   FIRST_CHUNK_PTR_SIZE=LARGEST;
	private static final ChunkPointer FIRST_CHUNK_PTR     =ChunkPointer.of(MAGIC_ID.limit()+FIRST_CHUNK_PTR_SIZE.bytes);
	
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
		
		AllocateTicket.bytes(metadata.calcSize())
		              .withApproval(c->c.getPtr().equals(FIRST_CHUNK_PTR))
		              .withDataPopulated((p, io)->{
			              ContiguousStructPipe.of(Metadata.class)
			                                  .write(io, metadata);
		              })
		              .submit(provider);
		
	}
	
	private static class Metadata extends IOInstance<Metadata>{
		
		@IODependency.VirtualNumSize(name="ayyyy", retention=GROW_ONLY)
		@IOValue
		public int value1=255;
		@IODependency.VirtualNumSize(name="ayyyy", retention=GROW_ONLY)
		@IOValue
		public int value2=255;
		
	}
	
	private final ChunkCache chunkCache=ChunkCache.weak();
	
	private final IOInterface   source;
	private final MemoryManager memoryManager=new VerySimpleMemoryManager(this);
	
	private ChunkPointer mainChunkPtr;
	
	
	public Cluster(IOInterface source) throws IOException{
		this.source=source;
		
		try(var io=source.read()){
			readMagic(io);
			mainChunkPtr=ChunkPointer.read(FIRST_CHUNK_PTR_SIZE, io);
		}
		
		var      ch=mainChunkPtr.dereference(this);
		Metadata m =new Metadata();
		try(var io=ch.io()){
			ContiguousStructPipe.of(Metadata.class)
			                    .read(io, m);
		}
		LogUtil.println(m);
		m.value1=20;
		LogUtil.println(m);
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
}
