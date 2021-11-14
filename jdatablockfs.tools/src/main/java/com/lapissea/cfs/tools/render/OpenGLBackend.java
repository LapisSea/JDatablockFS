package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.AtlasFont;
import com.lapissea.cfs.tools.DisplayManager;
import com.lapissea.cfs.tools.DrawFont;
import com.lapissea.cfs.tools.MSDFAtlas;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.MathUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.lapissea.util.PoolOwnThread.async;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUseProgram;
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
	
	private boolean destroyRequested=false;
	
	private final Deque<Runnable> glTasks=new LinkedList<>();
	
	private final Thread   glThread;
	private final DrawFont font;
	
	public final  GlfwWindow       window=new GlfwWindow();
	private final DisplayInterface displayInterface;
	
	private CompletableFuture<?> glInit;
	private Runnable             start;
	
	public OpenGLBackend(){
		Thread glThread=new Thread(this::displayLifecycle, "display");
		glThread.setDaemon(false);
		glThread.start();
		
		UtilL.sleepWhile(()->glInit==null);
		glInit.join();
		glInit=null;
		
		this.glThread=glThread;


//		var path="/roboto/regular";
		var path="/CourierPrime/Regular";

//		font=new TTFont(path+"/font.ttf", this, this::markFrameDirty, this::runLater);
		try{
			var atlas=new MSDFAtlas(path);
			
			font=new AtlasFont(atlas, this, this::markFrameDirty, this::runLater);
		}catch(IOException e){
			throw new RuntimeException("failed to load font", e);
		}
		
		displayInterface=new DisplayInterface(){
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
			public void registerDisplayResize(Runnable listener){
				window.size.register(listener);
			}
			@Override
			public void registerKeyboardButton(Consumer<KeyboardEvent> listener){
				window.registryKeyboardKey.register(glfwE->{
					listener.accept(new KeyboardEvent(switch(glfwE.getType()){
						case DOWN -> ActionType.DOWN;
						case UP -> ActionType.UP;
						case HOLD -> ActionType.HOLD;
					}, glfwE.getKey()));
				});
			}
			
			@Override
			public void registerMouseButton(Consumer<MouseEvent> listener){
				window.registryMouseButton.register(e->{
					listener.accept(new MouseEvent((switch(e.getKey()){
						case GLFW_MOUSE_BUTTON_LEFT -> MouseKey.LEFT;
						case GLFW_MOUSE_BUTTON_RIGHT -> MouseKey.RIGHT;
						default -> throw new NotImplementedException("unkown event "+e);
					}), switch(e.getType()){
						case DOWN -> ActionType.DOWN;
						case UP -> ActionType.UP;
						case HOLD -> ActionType.HOLD;
					}));
				});
			}
			@Override
			public void registerMouseScroll(Consumer<Integer> listener){
				window.registryMouseScroll.register(vec->listener.accept((int)vec.y()));
			}
			@Override
			public void registerMouseMove(Runnable listener){
				window.mousePos.register(listener);
			}
			@Override
			public boolean isMouseKeyDown(MouseKey key){
				return window.isMouseKeyDown(switch(key){
					case LEFT -> GLFW_MOUSE_BUTTON_LEFT;
					case RIGHT -> GLFW_MOUSE_BUTTON_RIGHT;
				});
			}
			@Override
			public boolean isOpen(){
				return !window.shouldClose();
			}
			@Override
			public void requestClose(){
				destroyRequested=true;
				window.requestClose();
			}
			@Override
			public void pollEvents(){
				window.pollEvents();
			}
			@Override
			public void destroy(){
				window.destroy();
			}
			@Override
			public void setTitle(String title){
				window.title.set(title);
			}
		};
	}
	
	
	private synchronized void initWindow(){
		GlfwMonitor.init();
		GLFWErrorCallback.createPrint(System.err).set();
		
		window.size.set(600, 600);
		window.centerWindow();
		
		var stateFile=new File("glfw-win.json");
		window.loadState(stateFile);
		new Thread(()->window.autoHandleStateSaving(stateFile), "glfw watch").start();
		
		window.onDestroy(()->{
			window.saveState(stateFile);
			System.exit(0);
		});
		
		org.lwjgl.glfw.GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 8);
		
		if(UtilL.sysPropertyByClass(DisplayManager.class, "emulateNoGLSupport").map(Boolean::parseBoolean).orElse(false)){
			throw new RuntimeException("gl disabled");
		}
		
		glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE);
		
		window.init(GlfwWindow.SurfaceAPI.OPENGL);
		
		window.grabContext();
		
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
		glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
		
		
		GL.createCapabilities();
		
		if(!destroyRequested){
			window.show();
		}else return;
		
		glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
	}
	
	private void displayLifecycle(){
		glInit=async(this::initWindow, Runnable::run);
		
		window.whileOpen(()->{
			UtilL.sleepWhile(()->start==null, 10);
			start.run();
		});
		destroyRequested=true;
		window.requestClose();
		window.destroy();
	}
	
	@Override
	public void start(Runnable start){
		this.start=start;
	}
	
	@Override
	public BulkDraw bulkDraw(DrawMode mode){
		return new BulkDrawGL(mode);
	}
	
	
	@Override
	public DisplayInterface getDisplay(){
		return displayInterface;
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
	public void postRender(){
		window.swapBuffers();
	}
	@Override
	public DrawFont getFont(){
		return font;
	}
	@Override
	public void preRender(){
		flushTasks();
		glDisable(GL_TEXTURE_2D);
		glUseProgram(0);
	}
	
	private void flushTasks(){
		synchronized(glTasks){
			while(!glTasks.isEmpty()){
				glTasks.removeFirst().run();
			}
		}
	}
	
	@Override
	public void clearFrame(){
		glViewport(0, 0, getDisplay().getWidth(), getDisplay().getHeight());
		glClear(GL_COLOR_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
	}
	
	
	@Override
	public void initRenderState(){
		glEnable(GL_BLEND);
		glDisable(GL_TEXTURE_2D);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		
		glLoadIdentity();
		translate(-1, 1);
		scale(2F/getDisplay().getWidth(), -2F/getDisplay().getHeight());
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
		var angle =-Math.atan2(xTo-xFrom, yTo-yFrom);
		var length=MathUtil.length(xFrom-xTo, yTo-yFrom);
		
		Point2D.Double  p=new Point2D.Double();
		AffineTransform t=new AffineTransform();
		t.setToIdentity();
		t.translate(xFrom, yFrom);
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
