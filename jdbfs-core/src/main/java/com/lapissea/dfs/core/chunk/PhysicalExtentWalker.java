package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.iterableplus.IterablePP;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class PhysicalExtentWalker implements IterablePP<PhysicalExtent>{
	
	private final DataProvider provider;
	private final ChunkPointer start;
	
	public PhysicalExtentWalker(DataProvider provider, ChunkPointer start){
		this.provider = provider;
		this.start = start;
	}
	
	public PhysicalExtentWalker(Chunk start){
		this(start.getDataProvider(), start.getPtr());
	}
	
	@Override
	public Iterator<PhysicalExtent> iterator(){
		return new Iterator<>(){
			private long position = start.getValue();
			
			@Override
			public boolean hasNext(){
				try{
					return position<provider.getSource().getIOSize();
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			
			@Override
			public PhysicalExtent next(){
				if(!hasNext()) throw new NoSuchElementException();
				try{
					var extent = PhysicalExtent.read(provider, ChunkPointer.of(position));
					position = extent.dataEnd();
					return extent;
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
}
