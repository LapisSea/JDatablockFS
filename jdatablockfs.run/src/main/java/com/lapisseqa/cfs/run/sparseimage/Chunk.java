package com.lapisseqa.cfs.run.sparseimage;


import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class Chunk extends IOInstance<Chunk>{
	
	@IOValue
	private int x;
	@IOValue
	private int y;
	
	@IOValue
	public float[] pixels;
	
	public Chunk(){
	
	}
	
	public Chunk(int x, int y, int chunkSize){
		this.x=x;
		this.y=y;
		pixels=new float[chunkSize*chunkSize*3];
	}
	
	public boolean isXY(int chunkX, int chunkY){
		return x==chunkX&&y==chunkY;
	}
}
