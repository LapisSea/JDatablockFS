package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.AtlasFont;
import com.lapissea.cfs.tools.GLFont;
import com.lapissea.cfs.tools.MSDFAtlas;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.MathUtil;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_INVALID_FRAMEBUFFER_OPERATION;

public class OpenGLBackend extends RenderBackend{
	
	public static void glErrorPrint(){
		int errorCode=glGetError();
		if(errorCode==GL_NO_ERROR) return;
		
		var err=switch(errorCode){
			case GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
			case GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
			case GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			case GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			default -> "Unknown error"+errorCode;
		};
		
		new RuntimeException(err).printStackTrace();
	}
	
	protected class BulkDrawGL extends BulkDraw{
		private boolean inactive;
		public BulkDrawGL(DrawMode mode){
			super(mode);
		}
		@Override
		protected void start(DrawMode mode){
			inactive=isBulkDrawing();
			if(inactive) return;
			
			glErrorPrint();
			glBegin(switch(mode){
				case QUADS -> GL_QUADS;
			});
		}
		@Override
		protected void end(){
			if(inactive) return;
			
			glEnd();
			glErrorPrint();
		}
	}
	
	private final Deque<Runnable> glTasks=new LinkedList<>();
	
	private final GlfwWindow window;
	private final Thread     glThread;
	private final GLFont     font;
	
	public OpenGLBackend(GlfwWindow window, Thread glThread){
		this.window=window;
		this.glThread=glThread;


//		var path="/roboto/regular";
		var path="/CourierPrime/Regular";

//		font=new TTFont(path+"/font.ttf", this::bulkDraw, this::markFrameDirty, this::runLater);
		try{
			var atlas=new MSDFAtlas(path);
			
			font=new AtlasFont(atlas, this::bulkDraw, this::markFrameDirty, this::runLater);
		}catch(IOException e){
			throw new RuntimeException("failed to load font", e);
		}
	}
	
	
	@Override
	public BulkDraw bulkDraw(DrawMode mode){
		return new BulkDrawGL(mode);
	}
	
	@Override
	public void runLater(Runnable task){
		if(Thread.currentThread()==glThread){
			task.run();
		}else{
			glTasks.add(task);
		}
	}
	
	@Override
	public int getWidth(){
		return window.size.x();
	}
	
	@Override
	public int getHeight(){
		return window.size.y();
	}
	@Override
	public int getMouseX(){
		return window.mousePos.x();
	}
	@Override
	public int getMouseY(){
		return window.mousePos.y();
	}
	
	@Override
	public void postRender(){
		window.swapBuffers();
	}
	@Override
	public void preRender(){
		synchronized(glTasks){
			while(!glTasks.isEmpty()){
				glTasks.removeFirst().run();
			}
		}
	}
	
	@Override
	public void clearFrame(){
		glViewport(0, 0, getWidth(), getHeight());
		glClear(GL_COLOR_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
	}
	
	
	@Override
	public void initRenderState(){
		glEnable(GL_BLEND);
		glDisable(GL_TEXTURE_2D);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		
		glLoadIdentity();
		translate(-1, 1);
		scale(2F/getWidth(), -2F/getHeight());
	}
	
	@Override
	public GLFont.Bounds getStringBounds(String str){
		return font.getStringBounds(str, getFontScale());
	}
	
	@Override
	public void outlineString(Color color, String str, float x, float y){
		font.outlineString(color, str, getFontScale(), x, y);
	}
	
	@Override
	public void fillString(Color color, String str, float x, float y){
		font.fillString(color, str, getFontScale(), x, y);
	}
	
	@Override
	public boolean canFontDisplay(char c){
		return font.canFontDisplay(c);
	}
	@Override
	public void translate(double x, double y){
		glTranslated(x, y, 0);
	}
	
	@Override
	public void scale(double x, double y){
		glScaled(x, y, 1);
	}
	
	@Override
	public void rotate(double angle){
		glRotated(angle, 0, 0, 1);
	}
	
	@Override
	public void pushMatrix(){
		glPushMatrix();
	}
	@Override
	public void popMatrix(){
		glPopMatrix();
	}
	
	@Override
	public void setColor(Color color){
		glErrorPrint();
		glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	
	@Override
	public Color readColor(){
		float[] color=new float[4];
		glGetFloatv(GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	@Override
	public void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		var pixelsPerByte=getPixelsPerByte();
		var angle        =-Math.atan2(xTo-xFrom, yTo-yFrom);
		var length       =MathUtil.length(xFrom-xTo, yTo-yFrom)*pixelsPerByte;
		
		Point2D.Double  p=new Point2D.Double();
		AffineTransform t=new AffineTransform();
		t.setToIdentity();
		t.translate(xFrom*pixelsPerByte, yFrom*pixelsPerByte);
		t.rotate(angle);
		
		double x    =-getLineWidth()/2;
		double width=getLineWidth();
		
		if(!isBulkDrawing()) glBegin(GL_QUADS);
		
		vertex2dCpuTrans(p, t, x, 0);
		vertex2dCpuTrans(p, t, x+width, 0);
		vertex2dCpuTrans(p, t, x+width, length);
		vertex2dCpuTrans(p, t, x, length);
		
		if(!isBulkDrawing()) glEnd();
	}
	private void vertex2dCpuTrans(Point2D.Double p, AffineTransform t, double x, double y){
		p.setLocation(x, y);
		t.transform(p, p);
		glVertex3d(p.x, p.y, 0);
	}
	
	
	@Override
	public void fillQuad(double x, double y, double width, double height){
		draw4Points(x, y, width, height, GL_QUADS);
	}
	@Override
	public void outlineQuad(double x, double y, double width, double height){
		draw4Points(x, y, width, height, GL_LINE_LOOP);
	}
	
	private void draw4Points(double x, double y, double width, double height, int drawMode){
		if(!isBulkDrawing()) glBegin(drawMode);
		glVertex3d(x, y, 0);
		glVertex3d(x+width, y, 0);
		glVertex3d(x+width, y+height, 0);
		glVertex3d(x, y+height, 0);
		if(!isBulkDrawing()) glEnd();
	}
}
