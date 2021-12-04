package com.lapisseqa.cfs.run.sparseimage;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;

public class Image extends IOInstance<Image>{
	
	private static final int chunkSize=8;
	
	@IOValue
	@IOValue.OverrideType(LinkedIOList.class)
	private IOList<Chunk> chunks;
	
	
	@SuppressWarnings("PointlessArithmeticExpression")
	public void set(int x, int y, float r, float g, float b) throws IOException{
		int chunkX=x/chunkSize;
		int chunkY=y/chunkSize;
		
		var iter=chunks.listIterator();
		while(iter.hasNext()){
			var c=iter.ioNext();
			if(!c.isXY(chunkX, chunkY)){
				continue;
			}
			
			int localX=x-chunkX*chunkSize;
			int localY=y-chunkY*chunkSize;
			int index =localX+localY*chunkSize;
			
			c.pixels[index*3+0]=r;
			c.pixels[index*3+1]=g;
			c.pixels[index*3+2]=b;
			
			iter.ioSet(c);
			return;
		}
		
		chunks.add(0, new Chunk(chunkX, chunkY, chunkSize));
		set(x, y, r, g, b);
	}
}
