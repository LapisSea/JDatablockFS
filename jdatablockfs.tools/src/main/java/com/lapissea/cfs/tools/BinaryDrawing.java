package com.lapissea.cfs.tools;


import com.lapissea.cfs.chunk.Chunk;

import java.awt.*;
import java.util.function.Function;

public abstract class BinaryDrawing{
	
	protected interface DrawB{
		void draw(int index, Color color, boolean withChar, boolean force);
	}
	
	protected static Color mul(Color color, float mul){
		return new Color(Math.round(color.getRed()*mul), Math.round(color.getGreen()*mul), Math.round(color.getBlue()*mul), color.getAlpha());
	}
	
	protected static Color add(Color color, Color other){
		return new Color(
			Math.min(255, color.getRed()+other.getRed()),
			Math.min(255, color.getGreen()+other.getGreen()),
			Math.min(255, color.getBlue()+other.getBlue()),
			Math.min(255, color.getAlpha()+other.getAlpha())
		);
	}
	
	protected static Color alpha(Color color, float alpha){
		return new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			(int)(alpha*255)
		);
	}
	
	protected static Color mix(Color color, Color other, float mul){
		return add(mul(color, 1-mul), mul(other, mul));
	}
	
	
	protected void drawArrow(int width, int from, int to){
		int xPosFrom=from%width, yPosFrom=from/width;
		int xPosTo  =to%width, yPosTo=to/width;
		
		double xFrom=xPosFrom+0.5, yFrom=yPosFrom+0.5;
		double xTo  =xPosTo+0.5, yTo=yPosTo+0.5;
		
		double xMid=(xFrom+xTo)/2, yMid=(yFrom+yTo)/2;
		
		double angle=Math.atan2(xTo-xFrom, yTo-yFrom);
		
		double arrowSize=0.4;
		
		double sin=Math.sin(angle)*arrowSize/2;
		double cos=Math.cos(angle)*arrowSize/2;
		
		drawLine(xMid+sin, yMid+cos, xMid-sin-cos, yMid-cos+sin);
		drawLine(xMid+sin, yMid+cos, xMid-sin+cos, yMid-cos-sin);
		drawLine(xFrom, yFrom, xTo, yTo);
	}
	
	protected void drawLine(int width, int from, int to){
		int xPosFrom=from%width, yPosFrom=from/width;
		int xPosTo  =to%width, yPosTo=to/width;
		
		drawLine(xPosFrom+0.5, yPosFrom+0.5, xPosTo+0.5, yPosTo+0.5);
	}
	
	protected Color chunkBaseColor(Chunk chunk){
		return Color.GREEN;
//		return chunk.isUsed()?chunk.isUserData()?Color.blue.brighter():Color.GREEN:Color.CYAN;
	}
	
	protected void fillChunk(DrawB drawByte, Chunk chunk, Function<Color, Color> filter){
		fillChunk(drawByte, chunk, filter, true, false);
	}
	
	protected void fillChunk(DrawB drawByte, Chunk chunk, Function<Color, Color> filter, boolean withChar, boolean force){
		
		var chunkColor=chunkBaseColor(chunk);
		var dataColor =mul(chunkColor, 0.5F);
		var freeColor =alpha(chunkColor, 0.4F);
		
		chunkColor=filter.apply(chunkColor);
		dataColor=filter.apply(dataColor);
		freeColor=filter.apply(freeColor);
		
		for(int i=(int)chunk.getPtr().getValue();i<chunk.dataStart();i++){
			drawByte.draw(i, chunkColor, false, false);
		}
		
		for(int i=0, j=(int)chunk.getCapacity();i<j;i++){
			drawByte.draw((int)(i+chunk.dataStart()), i>=chunk.getSize()?freeColor:dataColor, withChar, force);
		}
	}
	
	protected void fillBit(int index, float xOff, float yOff){
		int   xi =index%3;
		int   yi =index/3;
		float pxS=getPixelsPerByte()/3F;
		
		float x1=xi*pxS;
		float y1=yi*pxS;
		float x2=(xi+1)*pxS;
		float y2=(yi+1)*pxS;
		
		fillQuad(xOff+x1, yOff+y1, x2-x1, y2-y1);
	}
	
	
	protected abstract void fillQuad(double x, double y, double width, double height);
	
	protected abstract void outlineQuad(double x, double y, double width, double height);
	
	protected abstract int getPixelsPerByte();
	
	protected abstract void drawLine(double xFrom, double yFrom, double xTo, double yTo);
}
