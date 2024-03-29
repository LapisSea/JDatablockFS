package com.lapissea.dfs.run.sparseimage;


import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

@IOValue
public class Chunk extends IOInstance.Managed<Chunk>{
	
	private int           x;
	private int           y;
	public  Image.Pixel[] pixels;
	
	public Chunk(){
	
	}
	
	public Chunk(int x, int y, int chunkSize){
		this.x = x;
		this.y = y;
		pixels = new Image.Pixel[chunkSize*chunkSize];
		for(int i = 0; i<pixels.length; i++){
			pixels[i] = new Image.Pixel();
		}
	}
	
	public boolean isXY(int chunkX, int chunkY){
		return x == chunkX && y == chunkY;
	}
	@Override
	public String toShortString(){
		return "CH{" + x + ", " + y + "}";
	}
	@Override
	public String toString(){
		return "Chunk{" + x + ", " + y + "}";
	}
	public int getX(){
		return x;
	}
	public int getY(){
		return y;
	}
}
