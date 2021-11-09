package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.GLFont;

import java.awt.Color;

public abstract class RenderBackend{
	
	public enum DrawMode{
		QUADS
	}
	
	public abstract class BulkDraw implements AutoCloseable{
		
		private final boolean val;
		
		public BulkDraw(DrawMode mode){
			start(mode);
			val=bulkDrawing;
			bulkDrawing=true;
		}
		
		@Override
		public void close(){
			bulkDrawing=val;
			end();
		}
		
		protected abstract void start(DrawMode mode);
		protected abstract void end();
	}
	
	private boolean bulkDrawing;
	private float   fontScale;
	private float   lineWidth;
	
	private float pixelsPerByte=300;
	
	private boolean shouldRender=true;
	
	public float getPixelsPerByte(){
		return pixelsPerByte;
	}
	public void pixelsPerByteChange(float newPixelsPerByte){
		if(Math.abs(pixelsPerByte-newPixelsPerByte)<0.0001) return;
		pixelsPerByte=newPixelsPerByte;
		markFrameDirty();
	}
	
	public void markFrameDirty(){
		shouldRender=true;
	}
	public boolean notifyDirtyFrame(){
		if(!shouldRender) return false;
		shouldRender=false;
		return true;
	}
	
	public abstract BulkDraw bulkDraw(DrawMode mode);
	public boolean isBulkDrawing(){
		return bulkDrawing;
	}
	
	public void setFontScale(float fontScale){
		this.fontScale=fontScale;
	}
	public float getFontScale(){
		return fontScale;
	}
	
	public float getLineWidth(){
		return lineWidth;
	}
	public void setLineWidth(float line){
		lineWidth=line;
	}
	
	public abstract int getWidth();
	public abstract int getHeight();
	
	public abstract void runLater(Runnable task);
	
	public abstract int getMouseX();
	public abstract int getMouseY();
	
	public abstract void fillQuad(double x, double y, double width, double height);
	public abstract void outlineQuad(double x, double y, double width, double height);
	
	public abstract void drawLine(double xFrom, double yFrom, double xTo, double yTo);
	
	public abstract void setColor(Color color);
	public abstract void pushMatrix();
	public abstract void popMatrix();
	
	public abstract void translate(double x, double y);
	public abstract Color readColor();
	public abstract void initRenderState();
	
	public abstract void clearFrame();
	
	public abstract void scale(double x, double y);
	
	public abstract void rotate(double angle);
	
	public abstract void preRender();
	public abstract void postRender();
	
	public abstract GLFont.Bounds getStringBounds(String str);
	public abstract void outlineString(String str, float x, float y);
	public abstract void fillString(String str, float x, float y);
	public abstract boolean canFontDisplay(char c);
	public boolean canFontDisplay(int code){
		if(code==0) return false;
		return canFontDisplay((char)code);
	}
}
