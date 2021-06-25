package com.lapissea.cfs.tools;

import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.glfw.GlfwWindow.SurfaceAPI;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.event.change.ChangeRegistryInt;
import com.lapissea.vec.Vec2i;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

public class DisplayLWJGL extends BinaryDrawing implements DataLogger{
	
	protected class BulkDrawGL extends BulkDraw{
		
		public BulkDrawGL(DrawMode mode){
			super(mode);
		}
		@Override
		protected void start(DrawMode mode){
			if(isBulkDrawing()) GL11.glEnd();
			GL11.glBegin(switch(mode){
				case QUADS -> GL11.GL_QUADS;
			});
		}
		@Override
		protected void end(){
			GL11.glEnd();
		}
	}
	@Override
	protected BulkDraw bulkDraw(DrawMode mode){
		return new BulkDrawGL(mode);
	}
	
	private final GlfwWindow window=new GlfwWindow();
	
	private final List<Runnable> glTasks=Collections.synchronizedList(new LinkedList<>());
	
	private final List<CachedFrame> frames       =new ArrayList<>();
	private final ChangeRegistryInt pixelsPerByte=new ChangeRegistryInt(300);
	private final ChangeRegistryInt framePos     =new ChangeRegistryInt(0);
	private       boolean           shouldRender =true;
	
	private String  filter     ="";
	private boolean filterMake =false;
	private int[]   scrollRange=null;
	
	private final Thread glThread;
	
	private final TTFont font;
	
	public DisplayLWJGL(){
		glThread=new Thread(()->{
			
			initWindow();
			
			ChangeRegistryInt byteIndex=new ChangeRegistryInt(-1);
			byteIndex.register(e->shouldRender=true);
			framePos.register(e->shouldRender=true);
			
			double[] travel={0};
			
			window.registryMouseButton.register(e->{
				if(e.getKey()!=GLFW_MOUSE_BUTTON_LEFT) return;
				switch(e.getType()){
				case DOWN:
					travel[0]=0;
					break;
				case UP:
					if(travel[0]<30){
						ifFrame(MemFrame::printStackTrace);
					}
					break;
				}
				
			});
			window.registryMouseScroll.register(vec->setFrame(Math.max(0, (int)(getFramePos()+vec.y()))));
			
			
			Vec2i lastPos=new Vec2i();
			window.mousePos.register(pos->{
				
				travel[0]+=lastPos.distanceTo(pos);
				lastPos.set(pos);
				
				var pixelsPerByte=getPixelsPerByte();
				
				int xByte=window.mousePos.x()/pixelsPerByte;
				int yByte=window.mousePos.y()/pixelsPerByte;
				
				int width=Math.max(1, this.getWidth()/pixelsPerByte);
				
				byteIndex.set(yByte*width+xByte);
				
				if(!window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) return;
				
				float percent=MathUtil.snap((pos.x()-10F)/(window.size.x()-20F), 0, 1);
				if(scrollRange!=null){
					setFrame(Math.round(scrollRange[0]+(scrollRange[1]-scrollRange[0]+1)*percent));
				}else{
					setFrame(Math.round((frames.size()-1)*percent));
				}
			});
			
			window.size.register(()->{
				ifFrame(frame->calcSize(frame.data().length, true));
				render();
			});
			
			window.registryKeyboardKey.register(e->{
				if(!filter.isEmpty()&&e.getKey()==GLFW_KEY_ESCAPE){
					filter="";
					scrollRange=null;
					shouldRender=true;
				}
				
				if(filterMake){
					shouldRender=true;
					
					if(e.getType()!=GlfwKeyboardEvent.Type.UP&&e.getKey()==GLFW_KEY_BACKSPACE){
						if(!filter.isEmpty()){
							filter=filter.substring(0, filter.length()-1);
						}
						return;
					}
					
					if(e.getType()!=GlfwKeyboardEvent.Type.DOWN) return;
					if(e.getKey()==GLFW_KEY_ENTER){
						filterMake=false;
						
						
						boolean lastMatch=false;
						int     start    =0;
						
						int frameIndex=getFramePos();
						find:
						{
							for(int i=0;i<frames.size();i++){
								boolean match=!filter.isEmpty()&&Arrays.stream(frames.get(i).data().e().getStackTrace()).map(Object::toString).anyMatch(l->l.contains(filter));
								if(match==lastMatch){
									continue;
								}
								if(frameIndex>=start&&frameIndex<=i){
									scrollRange=new int[]{start, i};
									break find;
								}
								lastMatch=match;
								start=i;
							}
							int i=frames.size()-1;
							if(frameIndex>=start&&frameIndex<=i){
								scrollRange=new int[]{start, i};
							}
						}
						
						return;
					}
					if(e.getKey()==GLFW_KEY_V&&window.isKeyDown(GLFW_KEY_LEFT_CONTROL)){
						try{
							String data=(String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
							filter+=data;
						}catch(Exception ignored){ }
						return;
					}
					var cg=(char)e.getKey();
					if(!window.isKeyDown(GLFW_KEY_LEFT_SHIFT)) cg=Character.toLowerCase(cg);
					if(canFontDisplay(cg)){
						filter+=cg;
					}
					return;
				}else if(e.getKey()==GLFW_KEY_F){
					filter="";
					scrollRange=null;
					filterMake=true;
				}
				
				int delta;
				if(e.getKey()==GLFW_KEY_LEFT||e.getKey()==GLFW_KEY_A) delta=-1;
				else if(e.getKey()==GLFW_KEY_RIGHT||e.getKey()==GLFW_KEY_D) delta=1;
				else return;
				if(e.getType()==GlfwKeyboardEvent.Type.UP) return;
				setFrame(getFramePos()+delta);
			});
			
			window.autoF11Toggle();
			window.whileOpen(()->{
				if(shouldRender){
					shouldRender=false;
					render();
				}
				UtilL.sleep(0, 1000);
				window.pollEvents();
			});
			window.hide();
			
			window.destroy();
		}, "display");
		
		font=new TTFont("/CourierPrime-Regular.ttf", this::bulkDraw, ()->shouldRender=true, task->{
			if(Thread.currentThread()==glThread){
				task.run();
			}else{
				glTasks.add(task);
			}
		});
		
		glThread.setDaemon(false);
		glThread.start();
		
		
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		if(frames.isEmpty()) return;
		o.accept(frames.get(getFramePos()).data());
	}
	
	private synchronized void initWindow(){
		GlfwMonitor.init();
		GLFWErrorCallback.createPrint(System.err).set();
		
		
		window.title.set("Binary display - frame: "+"NaN");
		window.size.set(600, 600);
		window.centerWindow();
		
		var stateFile=new File("glfw-win.json");
		window.loadState(stateFile);
		new Thread(()->window.autoHandleStateSaving(stateFile), "glfw watch").start();
		
		window.onDestroy(()->{
			window.saveState(stateFile);
			System.exit(0);
		});
		
		GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 8);
		
		window.init(SurfaceAPI.OPENGL);
		
		window.grabContext();
		
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GL11.GL_TRUE);
		
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_DONT_CARE);
		
		
		GL.createCapabilities();
		glErrorPrint();
		
		window.show();
		
		
		GL11.glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(false);
		
		font.fillString("a", 8, -100, 0);
	}
	
	private void setFrame(int frame){
		if(frame>frames.size()-1) frame=frames.size()-1;
		framePos.set(frame);
		window.title.set("Binary display - frame: "+frame);
	}
	
	@Override
	protected int getFramePos(){
		synchronized(framePos){
			if(framePos.get()==-1) setFrame(frames.size()-1);
			return framePos.get();
		}
	}
	@Override
	protected void postRender(){
		window.swapBuffers();
	}
	@Override
	protected void preRender(){
		if(!glTasks.isEmpty()){
			glTasks.remove(glTasks.size()-1).run();
		}
	}
	
	@Override
	protected boolean isWritingFilter(){
		return filterMake;
	}
	@Override
	protected String getFilter(){
		return filter;
	}
	
	
	@Override
	protected void clearFrame(){
		GL11.glViewport(0, 0, getWidth(), getHeight());
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
	}
	
	
	@Override
	protected void initRenderState(){
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		
		GL11.glLoadIdentity();
		translate(-1, 1);
		scale(2F/getWidth(), -2F/getHeight());
	}
	
	@Override
	protected float[] getStringBounds(String str){
		return font.getStringBounds(str, getFontScale());
	}
	
	@Override
	protected void outlineString(String str, float x, float y){
		font.outlineString(str, getFontScale(), x, y);
		glErrorPrint();
	}
	
	@Override
	protected void fillString(String str, float x, float y){
		font.fillString(str, getFontScale(), x, y);
		glErrorPrint();
	}
	
	@Override
	protected boolean canFontDisplay(char c){
		return font.canFontDisplay(c);
	}
	@Override
	protected int getFrameCount(){
		return frames.size();
	}
	@Override
	protected CachedFrame getFrame(int index){
		return frames.get(index);
	}
	@Override
	protected void translate(double x, double y){
		GL11.glTranslated(x, y, 0);
	}
	
	@Override
	protected void scale(double x, double y){
		GL11.glScaled(x, y, 1);
	}
	
	@Override
	protected void rotate(double angle){
		GL11.glRotated(angle, 0, 0, 1);
	}
	
	@Override
	protected void pushMatrix(){
		GL11.glPushMatrix();
	}
	@Override
	protected void popMatrix(){
		GL11.glPopMatrix();
	}
	
	@Override
	protected void setColor(Color color){
		glErrorPrint();
		GL11.glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	
	@Override
	protected Color readColor(){
		float[] color=new float[4];
		GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	@Override
	protected void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		var pixelsPerByte=getPixelsPerByte();
		var angle        =-Math.toDegrees(Math.atan2(xTo-xFrom, yTo-yFrom));
		var length       =MathUtil.length(xFrom-xTo, yTo-yFrom)*pixelsPerByte;
		GL11.glPushMatrix();
		translate(xFrom*pixelsPerByte, yFrom*pixelsPerByte);
		rotate(angle);
		
		fillQuad(-getLineWidth()/2, 0, getLineWidth(), length);
		GL11.glPopMatrix();
	}
	
	
	@Override
	protected void fillQuad(double x, double y, double width, double height){
		if(!isBulkDrawing()) GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex3d(x, y, 0);
		GL11.glVertex3d(x+width, y, 0);
		GL11.glVertex3d(x+width, y+height, 0);
		GL11.glVertex3d(x, y+height, 0);
		if(!isBulkDrawing()) GL11.glEnd();
	}
	
	@Override
	protected void outlineQuad(double x, double y, double width, double height){
		if(!isBulkDrawing()) GL11.glBegin(GL11.GL_LINE_LOOP);
		GL11.glVertex3d(x, y, 0);
		GL11.glVertex3d(x+width, y, 0);
		GL11.glVertex3d(x+width, y+height, 0);
		GL11.glVertex3d(x, y+height, 0);
		if(!isBulkDrawing()) GL11.glEnd();
	}
	
	public static void glErrorPrint(){
		int errorCode=GL11.glGetError();
		if(errorCode==GL11.GL_NO_ERROR) return;
		
		new RuntimeException(switch(errorCode){
			case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL11.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
			case GL11.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
			case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			default -> "Unknown error"+errorCode;
		}).printStackTrace();
	}
	
	@Override
	public synchronized void log(MemFrame frame){
		frames.add(new CachedFrame(frame, new ParsedFrame(frames.size())));
		synchronized(framePos){
			framePos.set(-1);
		}
		shouldRender=true;
	}
	
	@Override
	public void finish(){ }
	
	@Override
	public void reset(){
		scrollRange=null;
		frames.clear();
		setFrame(0);
	}
	
	@Override
	protected int getWidth(){
		return window.size.x();
	}
	
	@Override
	protected int getHeight(){
		return window.size.y();
	}
	@Override
	protected int getMouseX(){
		return window.mousePos.x();
	}
	@Override
	protected int getMouseY(){
		return window.mousePos.y();
	}
	
	@Override
	public int getPixelsPerByte(){
		return pixelsPerByte.get();
	}
	@Override
	protected void pixelsPerByteChange(int newPixelsPerByte){
		pixelsPerByte.set(newPixelsPerByte);
	}
}
