package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.GLFont;

import java.awt.Color;
import java.util.function.Consumer;

public abstract class RenderBackend{
	
	public interface DisplayInterface{
		
		enum MouseKey{
			LEFT, RIGHT
		}
		
		enum ActionType{
			DOWN, UP, HOLD
		}
		
		record MouseEvent(MouseKey click, ActionType type){}
		
		record KeyboardEvent(ActionType type, int key){}
		
		int getWidth();
		int getHeight();
		
		int getMouseX();
		int getMouseY();
		
		void registerDisplayResize(Runnable listener);
		
		void registerKeyboardButton(Consumer<KeyboardEvent> listener);
		
		void registerMouseButton(Consumer<MouseEvent> listener);
		void registerMouseScroll(Consumer<Integer> listener);
		void registerMouseMove(Runnable listener);
		
		boolean isMouseKeyDown(MouseKey key);
		
		boolean isOpen();
		void requestClose();
		void pollEvents();
		void destroy();
	}
	
	
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
	
	private boolean shouldRender=true;
	
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
	
	public abstract DisplayInterface getDisplay();
	
	public abstract void runLater(Runnable task);
	
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
	public abstract void outlineString(Color color, String str, float x, float y);
	public abstract void fillString(Color color, String str, float x, float y);
	public abstract boolean canFontDisplay(char c);
	public boolean canFontDisplay(int code){
		if(code==0) return false;
		return canFontDisplay((char)code);
	}
}
