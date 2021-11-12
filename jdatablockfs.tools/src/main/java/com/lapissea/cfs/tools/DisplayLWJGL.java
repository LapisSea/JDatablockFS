package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.tools.render.OpenGLBackend;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.glfw.GlfwWindow.SurfaceAPI;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.vec.Vec2i;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.lapissea.util.PoolOwnThread.async;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;

public class DisplayLWJGL implements DataLogger{
	
	private boolean destroyRequested=false;
	
	private CompletableFuture<?> glInit;
	private GlfwWindow           window;
	
	private final OpenGLBackend renderer;
	
	private final SessionHost   sessionHost=new SessionHost();
	private final BinaryDrawing drawing;
	
	public DisplayLWJGL(){
		Thread glThread=new Thread(this::displayLifecycle, "display");
		renderer=new OpenGLBackend(glThread);
		window=renderer.window;
		
		drawing=new BinaryDrawing(renderer);
		
		glThread.setDaemon(false);
		glThread.start();
		
		UtilL.sleepWhile(()->glInit==null);
		glInit.join();
		glInit=null;
	}
	
	private void displayLifecycle(){
		glInit=async(this::initWindow, Runnable::run);
		
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
		
		window.registryMouseScroll.register(vec->drawing.displayedSession.ifPresent(ses->ses.setFrame(Math.max(0, (int)(drawing.getFramePos()-vec.y())))));
		
		
		Vec2i lastPos=new Vec2i();
		window.mousePos.register(pos->{
			
			travel[0]+=lastPos.distanceTo(pos);
			lastPos.set(pos);
			
			renderer.markFrameDirty();
			
			if(!window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) return;
			drawing.displayedSession.ifPresent(ses->{
				float percent=MathUtil.snap((pos.x()-10F)/(window.size.x()-20F), 0, 1);
				ses.setFrame(Math.round((ses.frames.size()-1)*percent));
			});
		});
		
		window.size.register(()->{
			ifFrame(frame->drawing.calcSize(frame.data().length, true));
			drawing.render();
		});
		
		window.registryKeyboardKey.register(e->{
			sessionHost.cleanUpSessions();
			if(e.getType()!=GlfwKeyboardEvent.Type.DOWN&&drawing.displayedSession.isPresent()){
				switch(e.getKey()){
					case GLFW_KEY_UP -> {
						sessionHost.nextSession();
						return;
					}
					case GLFW_KEY_DOWN -> {
						sessionHost.prevSession();
						return;
					}
				}
			}
			
			int delta;
			if(e.getKey()==GLFW_KEY_LEFT||e.getKey()==GLFW_KEY_A) delta=-1;
			else if(e.getKey()==GLFW_KEY_RIGHT||e.getKey()==GLFW_KEY_D) delta=1;
			else return;
			if(e.getType()==GlfwKeyboardEvent.Type.UP) return;
			drawing.displayedSession.ifPresent(ses->ses.setFrame(drawing.getFramePos()+delta));
		});
		
		window.autoF11Toggle();
		
		try{
			if(!destroyRequested){
				
				window.whileOpen(()->{
					sessionHost.cleanUpSessions();
					var activeSession=sessionHost.activeSession.get();
					if(!drawing.displayedSession.equals(activeSession)){
						drawing.displayedSession=activeSession;
						ifFrame(frame->drawing.calcSize(frame.data().length, true));
					}
					if(destroyRequested){
						destroyRequested=false;
						window.requestClose();
					}
					if(renderer.notifyDirtyFrame()){
						drawing.render();
					}
					UtilL.sleep(0, 1000);
					window.pollEvents();
					renderer.postRender();
					
				});
			}
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			window.destroy();
		}
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		drawing.displayedSession.map(s->s.frames).ifPresent(frames->{
			if(frames.isEmpty()) return;
			try{
				o.accept(frames.get(drawing.getFramePos()).data());
			}catch(IndexOutOfBoundsException ignored){}
		});
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
		
		if(UtilL.sysPropertyByClass(DisplayLWJGL.class, "emulateNoGLSupport").map(Boolean::parseBoolean).orElse(false)){
			throw new RuntimeException("gl disabled");
		}
		
		glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE);
		
		window.init(SurfaceAPI.OPENGL);
		
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
	
	@Override
	public DataLogger.Session getSession(String name){
		renderer.markFrameDirty();
		return sessionHost.getSession(name);
	}
	@Override
	public void destroy(){
		destroyRequested=true;
		sessionHost.destroy();
		drawing.displayedSession=Optional.empty();
		renderer.markFrameDirty();
	}
}
