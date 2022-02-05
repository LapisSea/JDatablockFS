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
	@Override
	public String toShortString(){
		return "CH{"+x+", "+y+"}";
	}
	@Override
	public String toString(){
		return "Chunk{"+x+", "+y+"}";
	}
	public int getX(){
		return x;
	}
	public int getY(){
		return y;
	}
}
