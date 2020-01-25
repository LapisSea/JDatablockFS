package com.lapissea.fsf.headermodules;

import com.lapissea.fsf.*;
import com.lapissea.util.UtilL;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.fsf.NumberSize.*;

public class FileHeaderModule extends HeaderModule{
	
	private FixedLenList<FixedNumber, ChunkPointer> list;
	
	public FileHeaderModule(Header header) throws IOException{
		super(header);
		list=new FixedLenList<>(new FixedNumber(LONG), getOwning().get(0), null);
	}
	
	@Override
	protected int getChunkCount(){
		return 1;
	}
	
	public FixedLenList<FixedNumber, ChunkPointer> getList(){
		return Objects.requireNonNull(list);
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		FixedLenList.init(out, new SizedNumber(BYTE, ()->200), config.freeChunkCapacity, false);
	}
	
	@Override
	public Iterable<SourcedPointer> getReferences() throws IOException{
		List<SourcedPointer> data=new ArrayList<>();
		
		var list=getList();
		
		try(var stream=getOwning().get(0).io().doRandom()){
			for(int i=0;i<list.size();i++){
				
				stream.setPos(list.calcPos(i));
				
				var off=stream.getGlobalPos();
				var val=list.getElement(i);
				
				data.add(new SourcedPointer(val, off));
			}
		}
		
		return data;
		
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
