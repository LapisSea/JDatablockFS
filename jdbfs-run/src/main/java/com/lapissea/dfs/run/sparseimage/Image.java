package com.lapissea.dfs.run.sparseimage;

import com.lapissea.dfs.config.ConfigUtils;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.io.IOException;

public class Image extends IOInstance.Managed<Image>{
	
	@IOValue
	public static class Pixel extends IOInstance.Managed<Pixel>{
		private float r;
		private float g;
		private float b;
		
		public Pixel(){ }
		
		public Pixel(float r, float g, float b){
			this.r = r;
			this.g = g;
			this.b = b;
		}
	}
	
	private static final int CHUNK_SIZE = ConfigUtils.optionalProperty("chunkSize").map(Integer::valueOf).orElse(4);
	
	@IOValue
	@IOValue.OverrideType(LinkedIOList.class)
	private IOList<Chunk> chunks;
	
	
	public void set(int x, int y, float r, float g, float b) throws IOException{
		int chunkX = x/CHUNK_SIZE;
		int chunkY = y/CHUNK_SIZE;
		
		var  dist     = Double.MAX_VALUE;
		long addIndex = 0;
		
		var iter = chunks.listIterator();
		while(iter.hasNext()){
			var c = iter.ioNext();
			if(!c.isXY(chunkX, chunkY)){
				int xDif = chunkX - c.getX();
				int yDif = chunkY - c.getY();
				var d    = Math.sqrt(xDif*xDif + yDif*yDif);
				if(d<dist){
					dist = d;
					addIndex = iter.nextIndex();
				}
				continue;
			}
			
			int localX = x - chunkX*CHUNK_SIZE;
			int localY = y - chunkY*CHUNK_SIZE;
			int index  = localX + localY*CHUNK_SIZE;
			
			c.pixels[index] = new Pixel(r, g, b);
			
			iter.ioSet(c);
			return;
		}
		
		var ch = new Chunk(chunkX, chunkY, CHUNK_SIZE);
		chunks.add(addIndex, ch);
		set(x, y, r, g, b);
	}
}
