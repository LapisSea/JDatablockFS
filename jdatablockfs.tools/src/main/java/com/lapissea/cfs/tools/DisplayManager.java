package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.tools.render.RenderBackend;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;
import com.lapissea.vec.Vec2i;

import java.util.Optional;
import java.util.function.Consumer;

import static com.lapissea.cfs.tools.render.RenderBackend.DisplayInterface.ActionType.DOWN;
import static com.lapissea.cfs.tools.render.RenderBackend.DisplayInterface.ActionType.UP;
import static org.lwjgl.glfw.GLFW.*;

public class DisplayManager implements DataLogger{
	
	private boolean destroyRequested=false;
	
	private final RenderBackend renderer;
	
	private final SessionHost        sessionHost=new SessionHost();
	private final BinaryGridRenderer gridRenderer;
	
	public DisplayManager(){
		renderer=RenderBackend.getBackendSources()
		                      .stream()
		                      .map(RenderBackend.CreatorSource::tryCreate)
		                      .filter(RenderBackend.CreatorSource.CreationAttempt::isOk)
		                      .findFirst()
		                      .orElseThrow()
		                      .backend();
		
		gridRenderer=new BinaryGridRenderer(renderer);
		Runnable updateTitle=()->{
			var f=sessionHost.activeFrame.get();
			renderer.getDisplay().setTitle(
				"Binary display - frame: "+(f==-1?"NaN":f)+
				sessionHost.activeSession.get().map(s->" - Session: "+s.getName()).orElse("")
			);
			renderer.markFrameDirty();
		};
		sessionHost.activeFrame.register(i->updateTitle.run());
		sessionHost.activeSession.register(ses->updateTitle.run());
		renderer.start(this::start);
		updateTitle.run();
	}
	
	private void start(){
		
		double[] travel={0};
		
		var display=renderer.getDisplay();
		display.registerMouseButton(e->{
			if(e.click()!=RenderBackend.DisplayInterface.MouseKey.LEFT) return;
			switch(e.type()){
				case DOWN -> travel[0]=0;
				case UP -> {
					if(travel[0]<30){
						ifFrame(MemFrame::printStackTrace);
					}
				}
			}
		});
		
		display.registerMouseScroll(delta->{
			gridRenderer.displayedSession.ifPresent(ses->{
				ses.setFrame(Math.max(0, gridRenderer.getFramePos()-delta));
			});
		});
		
		Vec2i lastPos=new Vec2i();
		display.registerMouseMove(()->{
			Vec2i pos=new Vec2i(display.getMouseX(), display.getMouseY());
			travel[0]+=lastPos.distanceTo(pos);
			lastPos.set(pos);
			
			renderer.markFrameDirty();
			
			if(!display.isMouseKeyDown(RenderBackend.DisplayInterface.MouseKey.LEFT)) return;
			
			gridRenderer.displayedSession.ifPresent(ses->{
				float percent=MathUtil.snap((pos.x()-10F)/(display.getWidth()-20F), 0, 1);
				ses.setFrame(Math.round((ses.frames.size()-1)*percent));
			});
		});
		
		display.registerDisplayResize(()->{
			sessionHost.cleanUpSessions();
			ifFrame(frame->gridRenderer.calcSize(frame.bytes().length, true));
			doRender();
		});
		
		display.registerKeyboardButton(e->{
			sessionHost.cleanUpSessions();
			if(e.type()!=DOWN&&gridRenderer.displayedSession.isPresent()){
				switch(e.key()){
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
			if(e.key()==GLFW_KEY_LEFT||e.key()==GLFW_KEY_A) delta=-1;
			else if(e.key()==GLFW_KEY_RIGHT||e.key()==GLFW_KEY_D) delta=1;
			else return;
			if(e.type()==UP) return;
			gridRenderer.displayedSession.ifPresent(ses->ses.setFrame(gridRenderer.getFramePos()+delta));
		});
		
		try{
			if(!destroyRequested){
				while(display.isOpen()){
					
					sessionHost.cleanUpSessions();
					var activeSession=sessionHost.activeSession.get();
					
					activeSession.ifPresent(ses->{
						if(ses.framePos.get()==-1){
							renderer.markFrameDirty();
						}
					});
					if(!gridRenderer.displayedSession.equals(activeSession)){
						gridRenderer.displayedSession=activeSession;
						ifFrame(frame->gridRenderer.calcSize(frame.bytes().length, true));
					}
					if(destroyRequested){
						destroyRequested=false;
						display.requestClose();
					}
					if(renderer.notifyDirtyFrame()){
						doRender();
					}
					UtilL.sleep(0, 1000);
					display.pollEvents();
				}
			}
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			display.destroy();
		}
	}
	
	private void doRender(){
		renderer.preRender();
		
		gridRenderer.render();
		
		renderer.postRender();
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		gridRenderer.displayedSession.map(s->s.frames).ifPresent(frames->{
			if(frames.isEmpty()) return;
			try{
				o.accept(frames.get(gridRenderer.getFramePos()).memData());
			}catch(IndexOutOfBoundsException ignored){}
		});
	}
	
	
	@Override
	public DataLogger.Session getSession(String name){
		return sessionHost.getSession(name);
	}
	@Override
	public void destroy(){
		destroyRequested=true;
		sessionHost.destroy();
		gridRenderer.displayedSession=Optional.empty();
		renderer.markFrameDirty();
	}
}
