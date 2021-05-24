package com.lapissea.cfs;

import com.lapissea.cfs.exceptions.MalformedFileException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.instancepipe.ContiguousPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOFieldDependency;
import com.lapissea.cfs.type.field.annotations.IOFieldMark;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import static com.lapissea.cfs.objects.NumberSize.*;
import static java.nio.charset.StandardCharsets.*;

public class Cluster{
	
	private static final byte[] MAGIC_ID="CC.DB/FS".getBytes(UTF_8);
	
	private static final NumberSize   FIRST_CHUNK_PTR_SIZE=LARGEST;
	private static final ChunkPointer FIRST_CHUNK_PTR     =ChunkPointer.of(MAGIC_ID.length+FIRST_CHUNK_PTR_SIZE.bytes);
	
	private static void readMagic(ContentReader src) throws IOException{
		var magicId=src.readInts1(MAGIC_ID.length);
		if(!Arrays.equals(magicId, MAGIC_ID)){
			throw new MalformedFileException(new String(magicId, UTF_8)+" is not a valid magic id");
		}
	}
	
	public static void init(IOInterface data) throws IOException{
		try(var io=data.write(true)){
			io.write(MAGIC_ID);
			FIRST_CHUNK_PTR_SIZE.write(io, FIRST_CHUNK_PTR);
			
			Metadata metadata=new Metadata();
			
			new Chunk(null, FIRST_CHUNK_PTR, metadata.calcSize(), 0).writeHeader(io);
			LogUtil.println(metadata);
			new ContiguousPipe().write(io, metadata);
		}
	}
	
	private static class Metadata extends IOInstance<Metadata>{
		
		@IOFieldMark
		public NumberSize ay=VOID;
		
		@IOFieldMark
		public int value=1111111111;
		
		@IOFieldMark
		public NumberSize lmao=VOID;
		
		@IOFieldDependency("value1Size")
		@IOFieldMark
		public int value1=255;
		
		@IOFieldMark
		public NumberSize value1Size=VOID;
		
	}
	
	private final Map<ChunkPointer, Chunk> chunkCache=new WeakValueHashMap<>();
	
	private final IOInterface source;
	
	private ChunkPointer mainChunkPtr;
	
	
	public Cluster(IOInterface source) throws IOException{
		this.source=source;
		
		try(var io=source.read()){
			readMagic(io);
			mainChunkPtr=ChunkPointer.read(FIRST_CHUNK_PTR_SIZE, io);
		}
		
		Metadata m=new Metadata();
		LogUtil.println(mainChunkPtr.dereference(this));
		LogUtil.println(Utils.byteArrayToBitString(mainChunkPtr.dereference(this).io().readInts1(2)));
		LogUtil.println(source.toString());
		new ContiguousPipe().read(mainChunkPtr.dereference(this).io(), m);
		LogUtil.println(m);
	}
	
	public Chunk getChunk(@NotNull ChunkPointer pointer) throws IOException{
		Objects.requireNonNull(pointer);
		
		var cached=chunkCache.get(pointer);
		if(cached!=null){
			var read=Chunk.readChunk(this, source, pointer);
			if(!read.equals(cached)) throw new IllegalStateException("Chunk cache desync\n"+TextUtil.toTable("read/cached", read, cached));
		}
		
		var read=Chunk.readChunk(this, source, pointer);
		chunkCache.put(pointer, read);
		return read;
	}
	
	public IOInterface getSource(){
		return source;
	}
}
