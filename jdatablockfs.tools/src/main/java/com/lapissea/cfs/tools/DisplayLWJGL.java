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
import com.lapissea.util.event.change.ChangeRegistryInt;
import com.lapissea.vec.Vec2i;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static com.lapissea.util.PoolOwnThread.async;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;

public class DisplayLWJGL extends BinaryDrawing implements DataLogger{
	
	private static class Session implements DataLogger.Session{
		private final List<CachedFrame> frames  =new ArrayList<>();
		private final ChangeRegistryInt framePos=new ChangeRegistryInt(0);
		
		private final Runnable    setDirty;
		private final IntConsumer onFrameChange;
		private       boolean     markForDeletion;
		
		private Session(Runnable setDirty, IntConsumer onFrameChange){
			this.setDirty=setDirty;
			this.onFrameChange=onFrameChange;
		}
		
		@Override
		public synchronized void log(MemFrame frame){
			frames.add(new CachedFrame(frame, new ParsedFrame(frames.size())));
			synchronized(framePos){
				framePos.set(-1);
			}
			setDirty.run();
		}
		
		@Override
		public void finish(){}
		
		@Override
		public void reset(){
			setDirty.run();
			frames.clear();
			setFrame(0);
		}
		
		@Override
		public void delete(){
			reset();
			markForDeletion=true;
		}
		
		private void setFrame(int frame){
			if(frame>frames.size()-1) frame=frames.size()-1;
			framePos.set(frame);
			onFrameChange.accept(frame);
		}
	}
	
	private final Map<String, Session> sessions        =new LinkedHashMap<>();
	private       Optional<Session>    activeSession   =Optional.empty();
	private       Optional<Session>    displayedSession=Optional.empty();
	private       boolean              destroyRequested=false;
	
	private CompletableFuture<?> glInit;
	GlfwWindow window;
	
	OpenGLBackend glRenderer;
	
	public DisplayLWJGL(){
		Thread glThread=new Thread(this::displayLifecycle, "display");
		glRenderer=new OpenGLBackend(glThread);
		window=glRenderer.window;
		setRenderer(glRenderer);
		
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
		
		window.registryMouseScroll.register(vec->displayedSession.ifPresent(ses->ses.setFrame(Math.max(0, (int)(getFramePos()-vec.y())))));
		
		
		Vec2i lastPos=new Vec2i();
		window.mousePos.register(pos->{
			
			travel[0]+=lastPos.distanceTo(pos);
			lastPos.set(pos);
			
			glRenderer.markFrameDirty();
			
			if(!window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) return;
			displayedSession.ifPresent(ses->{
				float percent=MathUtil.snap((pos.x()-10F)/(window.size.x()-20F), 0, 1);
					ses.setFrame(Math.round((ses.frames.size()-1)*percent));
			});
		});
		
		window.size.register(()->{
			ifFrame(frame->calcSize(frame.data().length, true));
			render();
		});
		
		window.registryKeyboardKey.register(e->{
			cleanUpSessions();
			if(e.getType()!=GlfwKeyboardEvent.Type.DOWN&&displayedSession.isPresent()&&sessions.size()>1){
				switch(e.getKey()){
					case GLFW_KEY_UP -> {
						boolean found=false;
						Session ses;
						find:
						{
							for(var value : sessions.values()){
								if(found){
									ses=value;
									break find;
								}
								found=value==displayedSession.get();
							}
							ses=sessions.values().iterator().next();
						}
						setActiveSession(ses);
						return;
					}
					case GLFW_KEY_DOWN -> {
						Session ses;
						find:
						{
							Session last=null;
							for(var value : sessions.values()){
								ses=last;
								last=value;
								if(value==displayedSession.get()){
									if(ses==null){
										for(var session : sessions.values()){
											last=session;
										}
										ses=last;
									}
									break find;
								}
							}
							ses=sessions.values().iterator().next();
						}
						setActiveSession(ses);
						return;
					}
				}
			}
			
			int delta;
			if(e.getKey()==GLFW_KEY_LEFT||e.getKey()==GLFW_KEY_A) delta=-1;
			else if(e.getKey()==GLFW_KEY_RIGHT||e.getKey()==GLFW_KEY_D) delta=1;
			else return;
			if(e.getType()==GlfwKeyboardEvent.Type.UP) return;
			displayedSession.ifPresent(ses->ses.setFrame(getFramePos()+delta));
		});
		
		window.autoF11Toggle();
		
		try{
			if(!destroyRequested){
				
				window.whileOpen(()->{
					cleanUpSessions();
					if(!displayedSession.equals(activeSession)){
						displayedSession=activeSession;
						ifFrame(frame->calcSize(frame.data().length, true));
					}
					if(destroyRequested){
						destroyRequested=false;
						window.requestClose();
					}
					if(glRenderer.notifyDirtyFrame()){
						render();
					}
					UtilL.sleep(0, 1000);
					window.pollEvents();
					glRenderer.postRender();
					
				});
			}
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			window.destroy();
		}
	}
	
	private void doRender(){
	
	}
	
	private void cleanUpSessions(){
		sessions.values().removeIf(s->s.markForDeletion);
		activeSession.filter(s->s.markForDeletion).flatMap(s->sessions.values().stream().findAny()).ifPresent(this::setActiveSession);
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		displayedSession.map(s->s.frames).ifPresent(frames->{
			if(frames.isEmpty()) return;
			try{
				o.accept(frames.get(getFramePos()).data());
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
	protected int getFramePos(){
		return displayedSession.map(ses->{
			if(ses.framePos.get()==-1) ses.setFrame(ses.frames.size()-1);
			return ses.framePos.get();
		}).orElse(0);
	}
	
	@Override
	protected int getFrameCount(){
		return displayedSession.map(s->s.frames.size()).orElse(0);
	}
	@Override
	protected CachedFrame getFrame(int index){
		return displayedSession.map(s->s.frames.get(index)).orElse(null);
	}
	
	
	@Override
	public DataLogger.Session getSession(String name){
		if(destroyRequested) return null;
		
		var ses=sessions.computeIfAbsent(
			name,
			nam->new Session(()->glRenderer.markFrameDirty(), frame->{
				glRenderer.markFrameDirty();
				window.title.set("Binary display - frame: "+frame+" @"+name);
			})
		);
		setActiveSession(ses);
		return ses;
	}
	@Override
	public void destroy(){
		destroyRequested=true;
		sessions.values().forEach(Session::finish);
		activeSession=Optional.empty();
		displayedSession=Optional.empty();
		sessions.clear();
	}
	
	private void setActiveSession(Session session){
		session.onFrameChange.accept(getFramePos());
		this.activeSession=Optional.of(session);
	}
}
