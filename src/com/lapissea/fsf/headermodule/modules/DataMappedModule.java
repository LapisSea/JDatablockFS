package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.FilePointer;
import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.SparsePointerList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.ContentOutputStream;

import java.awt.*;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;

public class DataMappedModule extends HeaderModule{
	
	private SparsePointerList<FilePointer> mappings;
	
	public DataMappedModule(Header header){
		super(header);
	}
	
	@Override
	protected int getOwningChunkCount(){
		return 1;
	}
	
	@Override
	protected void postRead() throws IOException{
		mappings=new SparsePointerList<>(()->new FilePointer(header), getOwning().get(0));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		SparsePointerList.init(out, new SizedNumber(NumberSize.BYTE, ()->0), config.fileTablePadding);
	}
	
	@Override
	public long capacityManager(Chunk chunk) throws IOException{
		long[] siz={0}, cap={0};
		chunk.calculateWholeChainSizes(siz, cap);
		return siz[0]*3/2;
	}
	
	public SparsePointerList<FilePointer> getMappings(){
		return Objects.requireNonNull(mappings);
	}
	
	@Override
	public Stream<ChunkLink> getReferenceStream() throws IOException{
		return Stream.concat(mappings.openLinkStream(),
		                     mappings.openValueLinkStream((ptr, mapping)->{
			                     var ch=ptr.dereference(header);
			
			                     var filePtr=new ChunkPointer(mapping.getStart());
			                     var source =ch.getDataStart();
			                     return new ChunkLink(filePtr, source);
		                     }));
	}
	
	@Override
	public Color displayColor(){
		return Color.GREEN.darker();
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return false;
	}
}
