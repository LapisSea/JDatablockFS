package com.lapissea.fsf.headermodule;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.util.ArrayViewList;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class HeaderModule{
	
	protected final Header      header;
	private         List<Chunk> owning;
	
	public HeaderModule(Header header){
		this.header=header;
	}
	
	public void read(Supplier<ChunkPointer> chunkIter) throws IOException{
		preRead();
		
		var owningArr=new Chunk[getOwningChunkCount()];
		for(int i=0;i<owningArr.length;i++){
			owningArr[i]=chunkIter.get().dereference(header);
		}
		
		owning=ArrayViewList.create(owningArr, null);
		
		postRead();
	}
	
	public void write(Consumer<ChunkPointer> dest) throws IOException{
		preWrite();
		for(Chunk chunk : owning){
			dest.accept(new ChunkPointer(chunk));
		}
		postWrite();
	}
	
	public List<Chunk> getOwning(){
		return Objects.requireNonNull(owning);
	}
	
	protected abstract int getOwningChunkCount();
	
	public abstract Stream<ChunkLink> getReferenceStream() throws IOException;
	
	public List<ChunkLink> getReferences() throws IOException{
		try(var refStream=getReferenceStream()){
			return refStream.collect(Collectors.toList());
		}
	}
	
	public abstract Color displayColor();
	
	public abstract boolean ownsBinaryOnly();
	
	protected void preRead() throws IOException  {}
	
	protected void postRead() throws IOException {}
	
	protected void preWrite() throws IOException {}
	
	protected void postWrite() throws IOException{}
	
	public abstract void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException;
	
	public abstract long capacityManager(Chunk chunk) throws IOException;
}
