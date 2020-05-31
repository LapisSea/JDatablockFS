package com.lapissea.fsf.headermodule.modules;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.collections.fixedlist.headers.FixedNumber;
import com.lapissea.fsf.headermodule.HeaderModule;
import com.lapissea.util.UtilL;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static com.lapissea.fsf.NumberSize.*;

public class FileHeaderModule<Identifier> extends HeaderModule<IOList<ChunkPointer>, Identifier>{
	
	
	public FileHeaderModule(Header<Identifier> header){
		super(header, o->{});
	}
	
	@Override
	public int getOwningChunkCount(){
		return 1;
	}
	
	@Override
	public List<Chunk> init() throws IOException{
		var pointerCount=header.modules.stream().mapToInt(HeaderModule::getOwningChunkCount).sum();
		return FixedLenList.init(header, new FixedNumber(LONG), pointerCount, false);
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
		return getValue().openLinkStream(ChunkPointer.CONVERTER);
	}
	
	@Override
	protected IOList<ChunkPointer> getValue(){
		if(super.getValue()==null){
			try{
				read(()->IOList.of(Header.FIRST_POINTER).makeReference(0));
			}catch(IOException e){
				e.printStackTrace();
			}
		}
		
		return super.getValue();
	}
	
	@Override
	public Stream<Chunk> getOwning(){
		try{
			return Stream.of(header.firstChunk());
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public IOList<ChunkPointer> getList(){
		return getValue();
	}
	
	@Override
	public Color displayColor(){
		return new Color(255, 82, 24);
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return true;
	}
	
	@Override
	protected FixedLenList<FixedNumber, ChunkPointer> postRead() throws IOException{
		return new FixedLenList<>(()->new FixedNumber(LONG), getOwning(0), null);
	}
}
