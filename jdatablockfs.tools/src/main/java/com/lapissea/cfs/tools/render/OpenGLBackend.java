package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.tools.AtlasFont;
import com.lapissea.cfs.tools.DrawFont;
import com.lapissea.cfs.tools.MSDFAtlas;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import org.lwjgl.glfw.GLFWErrorCallback;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.lapissea.glfw.GlfwWindow.Initializer.OpenGLSurfaceAPI.Profile.CORE;
import static com.lapissea.util.UtilL.async;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.GL_INVALID_FRAMEBUFFER_OPERATION;

public class OpenGLBackend extends RenderBackend{
	
	static{
		ImGuiUtils.load();
	}
	
	public static void glErrorPrint(){
		int errorCode = glGetError();
		if(errorCode == GL_NO_ERROR) return;
		
		var err = switch(errorCode){
			case GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
			case GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
			case GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			case GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			default -> "Unknown error" + errorCode;
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
			inactive = isBulkDrawing();
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
			debSwap();
			glErrorPrint();
		}
	}
	
	private boolean destroyRequested = false;
	
	private final Deque<Runnable> glTasks = new LinkedList<>();
	
	private final Thread   glThread;
	private final DrawFont font;
	
	public final  GlfwWindow       window = new GlfwWindow();
	private final DisplayInterface displayInterface;
	
	private CompletableFuture<?> glInit;
	private Runnable             start;
	
	private ImGuiImplGl3 imGuiGl3;
	
	public OpenGLBackend(){
		this.glThread = Thread.ofPlatform().name("display").daemon().start(this::displayLifecycle);

//		var path="/roboto/regular";
		var path = "/CourierPrime/Regular";
		
		try{
			var atlas = new MSDFAtlas(path);
			
			font = new AtlasFont(atlas, this, this::markFrameDirty, this::runLater);
		}catch(Throwable e){
			throw new RuntimeException("failed to load font", e);
		}
		
		UtilL.sleepWhile(() -> glInit == null);
		glInit.join();
		glInit = null;
		
		displayInterface = new DisplayInterface(){
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
				window.registryKeyboardKey.register(glfwE -> {
					listener.accept(new KeyboardEvent(switch(glfwE.getType()){
						case DOWN -> ActionType.DOWN;
						case UP -> ActionType.UP;
						case HOLD -> ActionType.HOLD;
					}, glfwE.getKey()));
				});
			}
			
			@Override
			public void registerMouseButton(Consumer<MouseEvent> listener){
				window.registryMouseButton.register(e -> {
					var key = switch(e.getKey()){
						case GLFW_MOUSE_BUTTON_LEFT -> MouseKey.LEFT;
						case GLFW_MOUSE_BUTTON_RIGHT -> MouseKey.RIGHT;
						default -> null;
					};
					if(key == null) return;
					listener.accept(new MouseEvent(key, switch(e.getType()){
						case DOWN -> ActionType.DOWN;
						case UP -> ActionType.UP;
						case HOLD -> ActionType.HOLD;
					}));
				});
			}
			@Override
			public void registerMouseScroll(Consumer<Integer> listener){
				window.registryMouseScroll.register(vec -> listener.accept((int)vec.y()));
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
					case MIDDLE -> GLFW_MOUSE_BUTTON_MIDDLE;
				});
			}
			@Override
			public boolean isOpen(){
				return !window.shouldClose();
			}
			@Override
			public void requestClose(){
				destroyRequested = true;
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
			@Override
			public boolean isFocused(){
				return window.isFocused();
			}
			@Override
			public int getPositionX(){
				return window.pos.x();
			}
			@Override
			public int getPositionY(){
				return window.pos.y();
			}
		};
	}
	
	
	private synchronized void initWindow(){
		if(GlobalConfig.configFlag("tools.emulateNoGL", false)){
			throw new RuntimeException("gl disabled");
		}
		
		GLFWErrorCallback.createPrint(System.err).set();
		
		window.size.set(600, 600);
		window.centerWindow();
		
		var stateFile = new File("glfw-win.json");
		window.autoHandleStateSaving(stateFile);
		window.autoF11Toggle();
		
		window.onDestroy(() -> {
			window.saveState(stateFile);
			System.exit(0);
		});
		
		
		window.init(
			s -> s.doubleBuffer(true)
			      .withOpenGL(
				      gl -> gl.withVersion(3.3)
				              .withSamples(8)
				              .withProfile(CORE)
				              .forwardCompatible()
				              .withDebugContext()
			      )
		);
		
		if(!destroyRequested){
			window.show();
		}else return;
		
		glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glfwSwapInterval(0);
		
		imGuiGl3 = ImGuiUtils.makeGL3Impl();
	}
	
	private void displayLifecycle(){
		glInit = async(this::initWindow, Runnable::run);
		
		window.whileOpen(() -> {
			UtilL.sleepWhile(() -> start == null, 10);
			start.run();
		});
		destroyRequested = true;
		if(window.isCreated()){
			window.requestClose();
			window.destroy();
		}
	}
	
	@Override
	public void start(Runnable start){
		this.start = start;
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
		if(Thread.currentThread() == glThread){
			task.run();
		}else{
			glTasks.add(task);
		}
	}
	
	@Override
	public void postRender(){
		var data = ImGui.getDrawData();
		if(data.ptr != 0) imGuiGl3.renderDrawData(data);
		
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
		debSwap();
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
		debSwap();
	}
	
	
	@Override
	public void initRenderState(){
		glEnable(GL_BLEND);
		glDisable(GL_TEXTURE_2D);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		
		glLoadIdentity();
		translate(-1, 1);
		scale(2F/getDisplay().getWidth(), -2F/getDisplay().getHeight());
		debSwap();
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
		float[] color = new float[4];
		glGetFloatv(GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	@Override
	public void drawLine(double xFrom, double yFrom, double xTo, double yTo){
		var angle  = -Math.atan2(xTo - xFrom, yTo - yFrom);
		var length = MathUtil.length(xFrom - xTo, yTo - yFrom);
		
		Point2D.Double  p = new Point2D.Double();
		AffineTransform t = new AffineTransform();
		t.setToIdentity();
		t.translate(xFrom, yFrom);
		t.rotate(angle);
		
		double x     = -getLineWidth()/2;
		double width = getLineWidth();
		
		if(!isBulkDrawing()) glBegin(GL_QUADS);
		
		vertex2dCpuTrans(p, t, x, 0);
		vertex2dCpuTrans(p, t, x + width, 0);
		vertex2dCpuTrans(p, t, x + width, length);
		vertex2dCpuTrans(p, t, x, length);
		
		if(!isBulkDrawing()){
			glEnd();
			debSwap();
		}
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
		glVertex3d(x + width, y, 0);
		glVertex3d(x + width, y + height, 0);
		glVertex3d(x, y + height, 0);
		if(!isBulkDrawing()){
			glEnd();
			debSwap();
		}
	}
	private void debSwap(){
		if(!DRAW_DEBUG) return;
		window.swapBuffers();
		window.pollEvents();
	}
}
