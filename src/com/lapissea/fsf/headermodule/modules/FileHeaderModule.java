package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.FixedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.util.UtilL;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.lapissea.fsf.NumberSize.*;

public class FileHeaderModule extends HeaderModule{
	
	private FixedLenList<FixedNumber, ChunkPointer> list;
	
	public FileHeaderModule(Header header) throws IOException{
		super(header);
		list=new FixedLenList<>(()->new FixedNumber(LONG), getOwning().get(0), null);
	}
	
	@Override
	protected int getOwningChunkCount(){
		return 1;
	}
	
	public FixedLenList<FixedNumber, ChunkPointer> getList(){
		return Objects.requireNonNull(list);
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		FixedLenList.init(out, new FixedNumber(LONG), config.freeChunkCapacity, false);
	}
	
	@Override
	public long capacityManager(Chunk chunk){
		throw new UnsupportedOperationException();
	}
	
	/**
	 * MUST CALL CLOSE ON STREAM
	 */
	@Override
	public Stream<ChunkLink> openReferenceStream() throws IOException{
		return getList().openLinkStream(e->e, (old, ptr)->ptr);
	}
	
	@Override
	public List<Chunk> getOwning(){
		try{
			return List.of(header.firstChunk());
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public Color displayColor(){
		return Color.RED;
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return true;
	}
}
