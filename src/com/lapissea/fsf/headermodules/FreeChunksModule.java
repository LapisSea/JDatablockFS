package com.lapissea.fsf.headermodules;

import com.lapissea.fsf.*;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.fsf.NumberSize.*;

public class FreeChunksModule extends HeaderModule{
	
	private FixedLenList<SizedNumber, ChunkPointer> list;
	
	public FreeChunksModule(Header header){
		super(header);
	}
	
	@Override
	protected int getChunkCount(){
		return 2;
	}
	
	public FixedLenList<SizedNumber, ChunkPointer> getList(){
		return Objects.requireNonNull(list);
	}
	
	@Override
	protected void postRead() throws IOException{
		list=new FixedLenList<>(new SizedNumber(header.source::getSize), getOwning().get(0), getOwning().get(1));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		FixedLenList.init(out, new SizedNumber(BYTE, ()->200), config.freeChunkCapacity, true);
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
	public Color displayColor(){
		return Color.BLUE.brighter();
	}
	
	@Override
	public boolean ownsBinaryOnly(){
		return true;
	}
}
