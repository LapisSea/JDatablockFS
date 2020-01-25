package com.lapissea.fsf.headermodules;

import com.lapissea.fsf.*;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DataMappedModule extends HeaderModule{
	private OffsetIndexSortedList<FilePointer> mappings;
	
	
	public DataMappedModule(Header header){
		super(header);
	}
	
	@Override
	protected int getChunkCount(){
		return 2;
	}
	
	@Override
	protected void postRead() throws IOException{
		mappings=new OffsetIndexSortedList<>(()->new FilePointer(header), getOwning().get(0), getOwning().get(1));
	}
	
	@Override
	public void init(ContentOutputStream out, FileSystemInFile.Config config) throws IOException{
		OffsetIndexSortedList.init(out, config.fileTablePadding, config);
	}
	
	public OffsetIndexSortedList<FilePointer> getMappings(){
		return Objects.requireNonNull(mappings);
	}
	
	@Override
	public Iterable<SourcedPointer> getReferences() throws IOException{
		List<SourcedPointer> data=new ArrayList<>();
		
		try(var stream=getOwning().get(0).io().doRandom()){
			for(int i=0, j=mappings.size();i<j;i++){
				long offset=mappings.getOffset(i);
				
				stream.setPos(offset);
				
				var off=stream.getGlobalPos();
				var val=new ChunkPointer(mappings.getByIndex(i).getStart());
				
				data.add(new SourcedPointer(val, off));
			}
		}
		
		return data;
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
