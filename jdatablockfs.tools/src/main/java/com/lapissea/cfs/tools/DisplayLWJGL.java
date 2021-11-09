package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwMonitor;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.glfw.GlfwWindow.SurfaceAPI;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.event.change.ChangeRegistry;
import com.lapissea.util.event.change.ChangeRegistryInt;
import com.lapissea.vec.Vec2i;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static com.lapissea.util.PoolOwnThread.async;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_INVALID_FRAMEBUFFER_OPERATION;

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
	@Override
	protected BulkDraw bulkDraw(DrawMode mode){
		return new BulkDrawGL(mode);
	}
	
	private final GlfwWindow window=new GlfwWindow();
	
	private final List<Runnable> glTasks=Collections.synchronizedList(new LinkedList<>());
	
	private final Map<String, Session>  sessions        =new LinkedHashMap<>();
	private       Optional<Session>     activeSession   =Optional.empty();
	private       Optional<Session>     displayedSession=Optional.empty();
	private final ChangeRegistry<Float> pixelsPerByte   =new ChangeRegistry<>(300F);
	private       boolean               shouldRender    =true;
	private       boolean               destroyRequested=false;
	
	private String  filter     ="";
	private boolean filterMake =false;
	private int[]   scrollRange=null;
	
	private       CompletableFuture<?> glInit;
	private final Thread               glThread;
	private final GLFont               font;
	
	public DisplayLWJGL(){
		glThread=new Thread(this::displayLifecycle, "display");

//		var path="/roboto/regular";
		var path="/CourierPrime/Regular";

//		font=new TTFont(path+"/font.ttf", this::bulkDraw, this::markFrameDirty, this::runInGl);
		try{
			var atlas=new MSDFAtlas(path);
			
			font=new AtlasFont(atlas, this::bulkDraw, this::markFrameDirty, this::runInGl);
		}catch(IOException e){
			throw new RuntimeException("failed to load font", e);
		}
		
		glThread.setDaemon(false);
		glThread.start();
		
		UtilL.sleepWhile(()->glInit==null);
		glInit.join();
		glInit=null;
	}
	
	private void markFrameDirty(){
		shouldRender=true;
	}
	private void runInGl(Runnable task){
		if(Thread.currentThread()==glThread){
			task.run();
		}else{
			glTasks.add(task);
		}
	}
	
	private void displayLifecycle(){
		glInit=async(this::initWindow, Runnable::run);
		
		ChangeRegistryInt byteIndex=new ChangeRegistryInt(-1);
		byteIndex.register(e->shouldRender=true);
		
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
			
			var pixelsPerByte=getPixelsPerByte();
			
			int xByte=(int)(window.mousePos.x()/pixelsPerByte);
			int yByte=(int)(window.mousePos.y()/pixelsPerByte);
			
			int width=(int)Math.max(1, this.getWidth()/pixelsPerByte);
			
			byteIndex.set(yByte*width+xByte);
			
			if(!window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) return;
			displayedSession.ifPresent(ses->{
				float percent=MathUtil.snap((pos.x()-10F)/(window.size.x()-20F), 0, 1);
				if(scrollRange!=null){
					ses.setFrame(Math.round(scrollRange[0]+(scrollRange[1]-scrollRange[0]+1)*percent));
				}else{
					ses.setFrame(Math.round((ses.frames.size()-1)*percent));
				}
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
						var frames=displayedSession.map(s->s.frames).orElse(List.of());
						
						for(int i=0;i<frames.size();i++){
							boolean match=!filter.isEmpty()&&filterMatchAt(i);
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
					}catch(Exception ignored){}
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
			displayedSession.ifPresent(ses->ses.setFrame(getFramePos()+delta));
		});
		
		window.autoF11Toggle();

//		var imguiCtx =new Context();
//		var imguiImpl=new ImplGL3();
		
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
					if(shouldRender){
						shouldRender=false;
						render();
					}
					UtilL.sleep(0, 1000);
					window.pollEvents();
					postRender();
					
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
		glErrorPrint();
		
		if(!destroyRequested){
			window.show();
		}else return;
		
		glClearColor(0.5F, 0.5F, 0.5F, 1.0f);
		
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		
		font.fillString("a", 8, -100, 0);
	}
	
	@Override
	protected int getFramePos(){
		return displayedSession.map(ses->{
			if(ses.framePos.get()==-1) ses.setFrame(ses.frames.size()-1);
			return ses.framePos.get();
		}).orElse(0);
	}
	@Override
	protected void postRender(){
		window.swapBuffers();
	}
	@Override
	protected void preRender(){
		while(!glTasks.isEmpty()){
			glTasks.remove(0).run();
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
		glViewport(0, 0, getWidth(), getHeight());
		glClear(GL_COLOR_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
	}
	
	
	@Override
	protected void initRenderState(){
		glEnable(GL_BLEND);
		glDisable(GL_TEXTURE_2D);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		
		glLoadIdentity();
		translate(-1, 1);
		scale(2F/getWidth(), -2F/getHeight());
	}
	
	@Override
	protected GLFont.Bounds getStringBounds(String str){
		return font.getStringBounds(str, getFontScale());
	}
	
	@Override
	protected void outlineString(String str, float x, float y){
		font.outlineString(str, getFontScale(), x, y);
	}
	
	@Override
	protected void fillString(String str, float x, float y){
		font.fillString(str, getFontScale(), x, y);
	}
	
	@Override
	protected boolean canFontDisplay(char c){
		return font.canFontDisplay(c);
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
	protected void translate(double x, double y){
		glTranslated(x, y, 0);
	}
	
	@Override
	protected void scale(double x, double y){
		glScaled(x, y, 1);
	}
	
	@Override
	protected void rotate(double angle){
		glRotated(angle, 0, 0, 1);
	}
	
	@Override
	protected void pushMatrix(){
		glPushMatrix();
	}
	@Override
	protected void popMatrix(){
		glPopMatrix();
	}
	
	@Override
	protected void setColor(Color color){
		glErrorPrint();
		glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	
	@Override
	protected Color readColor(){
		float[] color=new float[4];
		glGetFloatv(GL_CURRENT_COLOR, color);
		return new Color(color[0], color[1], color[2], color[3]);
	}
	
	@Override
	protected void drawLine(double xFrom, double yFrom, double xTo, double yTo){
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
	protected void fillQuad(double x, double y, double width, double height){
		draw4Points(x, y, width, height, GL_QUADS);
	}
	@Override
	protected void outlineQuad(double x, double y, double width, double height){
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
	
	@Override
	public DataLogger.Session getSession(String name){
		if(destroyRequested) return null;
		
		var ses=sessions.computeIfAbsent(
			name,
			nam->new Session(()->shouldRender=true, frame->{
				shouldRender=true;
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
	public float getPixelsPerByte(){
		return pixelsPerByte.get();
	}
	@Override
	protected void pixelsPerByteChange(float newPixelsPerByte){
		pixelsPerByte.set(newPixelsPerByte);
	}
	
	private void setActiveSession(Session session){
		session.onFrameChange.accept(getFramePos());
		this.activeSession=Optional.of(session);
	}
}
