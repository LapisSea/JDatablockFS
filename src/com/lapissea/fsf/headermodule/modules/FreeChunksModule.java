package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.ContentOutputStream;

import java.awt.*;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.fsf.NumberSize.*;

public class FreeChunksModule extends HeaderModule{
	
	private final Supplier<SizedNumber> headerSupplier=()->new SizedNumber(header.source::getSize);
	
	private FixedLenList<SizedNumber, ChunkPointer> list;
	
	public FreeChunksModule(Header header){
		super(header);
	}
	
	@Override
	protected int getOwningChunkCount(){
		return 2;
	}
	
	public FixedLenList<SizedNumber, ChunkPointer> getList(){
		return Objects.requireNonNull(list);
	}
	
	@Override
	protected void postRead() throws IOException{
		list=new FixedLenList<>(headerSupplier, getOwning().get(0), getOwning().get(1));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		FixedLenList.init(out, new SizedNumber(BYTE, ()->200), config.freeChunkCapacity, true);
	}
	
	@Override
	public long capacityManager(Chunk chunk) throws IOException{
		long[] siz={0}, cap={0};
		chunk.calculateWholeChainSizes(siz, cap);
		return siz[0]*2;
	}
	
	/**
	 * MUST CALL CLOSE ON STREAM
	 */
	@Override
	public Stream<ChunkLink> getReferenceStream() throws IOException{
		return getList().openLinkStream(Function.identity());
	}
	
	@Override
	public Color displayColor(){
		return Color.BLUE.brighter();
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return true;
	}
}
