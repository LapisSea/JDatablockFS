package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.tools.render.RenderBackend;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.*;
import com.lapissea.vec.Vec2iFinal;
import com.lapissea.vec.interf.IVec2iR;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiConfigFlags;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.lapissea.cfs.tools.render.RenderBackend.DisplayInterface.ActionType.DOWN;
import static com.lapissea.cfs.tools.render.RenderBackend.DisplayInterface.ActionType.UP;
import static org.lwjgl.glfw.GLFW.*;

public class DisplayManager implements DataLogger{
	
	private static final boolean DO_JIT_WARMUP =UtilL.sysPropertyByClass(DisplayManager.class, "DO_JIT_WARMUP", true, Boolean::parseBoolean);
	private static final boolean LOG_FRAME_TIME=UtilL.sysPropertyByClass(DisplayManager.class, "LOG_FRAME_TIME", false, Boolean::parseBoolean);
	
	static{
		try{
			System.setProperty("imgui.library.path", "./lib");
			ImGui.createContext();
		}catch(Throwable e){
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	private boolean destroyRequested=false;
	
	private final RenderBackend renderer;
	
	private final SessionHost        sessionHost=new SessionHost();
	private final BinaryGridRenderer gridRenderer;
	
	public DisplayManager(){
		
		renderer=createBackend();
		
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
	
	private RenderBackend createBackend(){
		var fails=new LinkedList<Throwable>();
		
		for(var source : RenderBackend.getBackendSources()){
			try{
				return source.create();
			}catch(Throwable e){
				fails.add(e);
			}
		}
		
		var e=new RuntimeException("Failed to create render display");
		fails.forEach(e::addSuppressed);
		throw e;
	}
	
	private final EnumSet<RenderBackend.DisplayInterface.MouseKey> justPressedKeys=EnumSet.noneOf(RenderBackend.DisplayInterface.MouseKey.class);
	private final EnumSet<RenderBackend.DisplayInterface.MouseKey> lastDownKeys   =EnumSet.noneOf(RenderBackend.DisplayInterface.MouseKey.class);
	private final EnumSet<RenderBackend.DisplayInterface.MouseKey> downKeys       =EnumSet.noneOf(RenderBackend.DisplayInterface.MouseKey.class);
	private void start(){
		
		var display=renderer.getDisplay();
		display.registerMouseButton(e->{
			justPressedKeys.add(e.click());
			switch(e.type()){
				case DOWN -> downKeys.add(e.click());
				case UP -> downKeys.remove(e.click());
			}
			renderer.markFrameDirty();
		});
		
		display.registerMouseScroll(delta->{
			
			new Thread(()->{
				int     steps =5;
				float[] deltas=new float[steps];
				for(int i=0;i<deltas.length;i++){
					var val=steps-i;
					deltas[i]=val*val;
				}
				
				float sum=0;
				for(float v : deltas){
					sum+=v;
				}
				for(int i=0;i<deltas.length;i++){
					deltas[i]/=sum*2;
					deltas[i]*=delta;
				}
				
				for(int i=0;i<deltas.length;i++){
					ImGui.getIO().setMouseWheel(ImGui.getIO().getMouseWheel()+deltas[i]);
					renderer.markFrameDirty();
					UtilL.sleep(16);
				}
			}).start();
			
			ImGui.getIO().setMouseWheel(delta);
			if(ImGui.getIO().getWantCaptureMouse()){
				renderer.markFrameDirty();
				return;
			}
			
			gridRenderer.displayedSession.ifPresent(ses->{
				ses.setFrame(Math.max(0, gridRenderer.getFramePos()-delta));
			});
		});
		
		display.registerMouseMove(()->{
			renderer.markFrameDirty();
			
			if(!display.isMouseKeyDown(RenderBackend.DisplayInterface.MouseKey.LEFT)) return;
			if(ImGui.getIO().getWantCaptureMouse()) return;
			
			gridRenderer.displayedSession.ifPresent(ses->{
				float percent=MathUtil.snap((display.getMouseX()-10F)/(display.getWidth()-20F), 0, 1);
				ses.setFrame(Math.round((ses.frames.size()-1)*percent));
			});
		});
		
		display.registerDisplayResize(()->{
			sessionHost.cleanUpSessions();
			ifFrame(frame->gridRenderer.calcSize(frame.bytes().length, true));
			renderer.markFrameDirty();
			renderer.runLater(()->{
				if(renderer.notifyDirtyFrame()){
					doRender();
				}
			});
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
				int  jitWarmup    =DO_JIT_WARMUP?0:Integer.MAX_VALUE;
				long lastFrameTime=0;
				while(display.isOpen()){
					
					sessionHost.cleanUpSessions();
					var activeSession=sessionHost.activeSession.get();
					
					if(activeSession.isPresent()){
						if(activeSession.get().framePos.get()==-1){
							renderer.markFrameDirty();
						}
						
						if(jitWarmup<150&&Rand.b(0.1F)){
							jitWarmup++;
							renderer.markFrameDirty();
						}
					}else{
						if(jitWarmup<20&&Rand.b(0.1F)){
							jitWarmup++;
							renderer.markFrameDirty();
						}
					}
					
					if(!gridRenderer.displayedSession.equals(activeSession)){
						gridRenderer.displayedSession=activeSession;
						ifFrame(frame->gridRenderer.calcSize(frame.bytes().length, true));
					}
					if(destroyRequested){
						destroyRequested=false;
						display.requestClose();
					}
					long tim=System.currentTimeMillis();
					if(tim-lastFrameTime>1000){
						lastFrameTime=tim;
						renderer.markFrameDirty();
					}
					
					if(renderer.notifyDirtyFrame()){
						doRender();
					}else UtilL.sleep(16);
					if(jitWarmup>=150) UtilL.sleep(0, 1000);
					display.pollEvents();
				}
			}
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			display.destroy();
		}
	}
	
	private final NanoTimer timer=LOG_FRAME_TIME?new NanoTimer():null;
	
	private List<Object[]> hover;
	
	private void doRender(){
		updateImgui();
		
		renderer.preRender();
		
		if(LOG_FRAME_TIME){
			timer.end();
		}
		
		var newHover=gridRenderer.render();
		
		if(LOG_FRAME_TIME){
			timer.start();
			
			LogUtil.println(timer.msAvrg100());
		}
		
		if(!ImGui.getIO().getWantCaptureMouse()){
			hover=newHover;
		}
		
		ImGui.newFrame();
		drawImgui();
		ImGui.render();
		
		renderer.postRender();
		
		if(!lastDownKeys.equals(downKeys)){
			renderer.markFrameDirty();
			renderer.runLater(renderer::markFrameDirty);
			lastDownKeys.clear();
			lastDownKeys.addAll(downKeys);
		}
	}
	
	private final boolean[] cfWrap={false}, skipImpl={true};
	private void drawImgui(){
		ifFrame(memFrame->{
			ImGui.begin("Current Frame:");
			
			if(!ImGui.isWindowCollapsed()){
				clampWindow();
				
				sessionHost.activeSession.get().ifPresent(s->{
					ImGui.text("Session: "+s.getName());
					ImGui.text("Frame: "+(sessionHost.activeFrame.get()+1)+"/"+s.frames.size());
				});
				
				ImGui.newLine();
				
				ImGui.text("Write event stack trace:");
				ImGui.separator();
				
				showTrace(memFrame.e(), cfWrap, skipImpl);
			}
			
			ImGui.end();
		});
		gridRenderer.displayedSession.map(s->s.frames).ifPresent(frames->{
			if(frames.isEmpty()) return;
			SessionHost.ParsedFrame f;
			try{
				f=frames.get(gridRenderer.getFramePos()).parsed();
			}catch(IndexOutOfBoundsException ignored){
				return;
			}
			var err=f.displayError;
			if(err==null) return;
			
			
			ImGui.begin("Display Error:");
			
			clampWindow();
			
			ImGui.text("Breakdown:");
			ImGui.separator();
			var msg=DrawUtils.errorToMessage(err);
			ImGui.text(msg);
			
			ImGui.newLine();
			ImGui.text("Full stack trace:");
			ImGui.separator();
			showTrace(MemFrame.errorToStr(err), cfWrap, skipImpl);
			
			ImGui.end();
		});
		
		
		if(!hover.isEmpty()){
			ImGui.begin("Hover data:");
			for(Object[] objects : hover){
				for(Object object : objects){
					ImGui.sameLine();
					if(object instanceof String str) ImGui.textColored(200, 255, 200, 255, str);
					else{
						switch(object){
							case IOInstance inst -> {
								var str=inst.getThisStruct().instanceToString(null, inst, false, "{\n\t", "\n}", ": ", ",\n\t");
								
								if(str==null) str="";
								ImGui.textColored(100, 100, 255, 255, str);
							}
							case BinaryGridRenderer.FieldVal inst -> {
								var str=inst.field().instanceToString(inst.ioPool(), inst.instance(), false, "{\n\t", "\n}", ": ", ",\n\t");
								
								if(str==null) str="";
								ImGui.textColored(100, 255, 100, 255, str);
							}
							default -> {
								var str=Objects.toString(object);
								
								if(str==null) str="";
								ImGui.text(str);
							}
						}
					}
				}
				ImGui.separator();
			}
			ImGui.end();
		}
		
		lastSiz=new Vec2iFinal(renderer.getDisplay().getWidth(), renderer.getDisplay().getHeight());
	}
	
	private void clampWindow(){
		if(lastSiz.equals(renderer.getDisplay().getWidth(), renderer.getDisplay().getHeight())){
			return;
		}
		
		var siz=ImGui.getWindowSize();
		var pos=ImGui.getWindowPos();
		var io =ImGui.getIO();
		var w  =io.getDisplaySizeX();
		
		var right=siz.x+pos.x;
		if(right>w){
			if(pos.x>0.0001F){
				pos.x=Math.max(0, pos.x-(right-w));
				ImGui.setWindowPos(pos.x, pos.y);
				renderer.markFrameDirty();
				clampWindow();
				return;
			}
		}
		
		if(Math.abs(pos.x)<0.0001F){
			var dist=Math.abs(siz.x-lastSiz.x());
			if(dist<0.0001F){
				ImGui.setWindowSize(w, siz.y);
				renderer.markFrameDirty();
				return;
			}
		}
		var diff=siz.x-w;
		if(diff>0){
			ImGui.setWindowSize(w, siz.y);
			renderer.markFrameDirty();
		}
	}
	
	private void showTrace(String trace, boolean[] cfWrap, boolean[] skipImpl){
		if(ImGui.button("Wrap")){
			cfWrap[0]=!cfWrap[0];
		}
		ImGui.sameLine();
		ImGui.text(cfWrap[0]?"Y":"N");
		
		if(ImGui.button("Skip impl package")){
			skipImpl[0]=!skipImpl[0];
		}
		ImGui.sameLine();
		ImGui.text(skipImpl[0]?"Y":"N");
		
		if(ImGui.button("Print to console")){
			LogUtil.println(trace);
		}
		
		var style  =ImGui.getStyle();
		var padding=style.getItemSpacing();
		style.setItemSpacing(0, 0);
		
		ImGui.newLine();
		
		Consumer<String> pushLine=line->{
			{
				var     at="\tat ";
				Pattern p =Pattern.compile(at+".+/");
				Matcher m =p.matcher(line);
				if(m.find()){
					var module=line.substring(m.start()+at.length(), m.end());
					ImGui.sameLine();
					ImGui.textColored(255, 100, 100, 255, line.substring(0, at.length()));
					ImGui.sameLine();
					ImGui.textColored(255, 200, 200, 255, module);
					line=line.substring(m.end());
				}
			}
			
			String loc="";
			
			Pattern p=Pattern.compile("\\(.+:[0-9]+\\)$");
			Matcher m=p.matcher(line);
			if(m.find()){
				loc=line.substring(m.start()+1, m.end()-1);
				line=line.substring(0, m.start());
			}
			
			String funct="";
			if(!loc.isEmpty()){
				funct=line.substring(line.lastIndexOf('.')+1);
				line=line.substring(0, line.length()-funct.length());
			}
			
			
			for(int i=0;i<line.length();i++){
				char c=line.charAt(i);
				ImGui.sameLine();
				var cs=""+c;
				switch(c){
					case '.' -> ImGui.textColored(255, 100, 100, 255, cs);
					default -> ImGui.text(cs);
				}
			}
			if(!funct.isEmpty()){
				ImGui.sameLine();
				ImGui.textColored(200, 200, 255, 255, funct);
			}
			if(!loc.isEmpty()){
				ImGui.sameLine();
				ImGui.textColored(100, 255, 100, 255, "(");
				ImGui.sameLine();
				ImGui.textColored(200, 255, 200, 255, loc);
				ImGui.sameLine();
				ImGui.textColored(100, 255, 100, 255, ")");
			}
		};
		
		var w           =ImGui.getWindowContentRegionMaxX()-10;
		var siz         =new ImVec2();
		int skippedLines=0;
		for(var e : trace.split("\n")){
			if(skipImpl[0]&&e.contains(".impl.")&&!e.contains("Transaction")){
				skippedLines++;
				continue;
			}
			if(skippedLines>0){
				ImGui.sameLine();
				pushLine.accept("\t... "+skippedLines+" lines");
				skippedLines=0;
			}
			if(cfWrap[0]){
				String str="";
				for(int i=0;i<e.length();i++){
					var newStr=str+e.charAt(i);
					ImGui.calcTextSize(siz, newStr);
					if(siz.x>w){
						ImGui.newLine();
						pushLine.accept(str);
						str="";
						i--;
						continue;
					}
					str=newStr;
				}
				if(!str.isEmpty()){
					ImGui.newLine();
					pushLine.accept(str);
				}
			}else{
				ImGui.newLine();
				pushLine.accept(e);
			}
		}
		
		style.setItemSpacing(padding.x, padding.y);
	}
	
	private double  time=0.0;
	private IVec2iR lastSiz;
	private void updateImgui(){
		int w=renderer.getDisplay().getWidth(), h=renderer.getDisplay().getHeight();
		if(lastSiz==null) lastSiz=new Vec2iFinal(w, h);
		
		var io=ImGui.getIO();
		io.setDisplaySize(w, h);
		
		final double currentTime=glfwGetTime();
		io.setDeltaTime(time>0.0?(float)(currentTime-time):1.0f/60.0f);
		time=currentTime;
		
		updateMousePosAndButtons();
	}
	
	private void updateMousePosAndButtons(){
		var io=ImGui.getIO();
		var d =renderer.getDisplay();
		
		for(var key : RenderBackend.DisplayInterface.MouseKey.values()){
			io.setMouseDown(key.id, justPressedKeys.contains(key)||downKeys.contains(key));
		}
		justPressedKeys.clear();
		
		var mousePosBackup=new ImVec2();
		
		io.getMousePos(mousePosBackup);
		io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE);
		io.setMouseHoveredViewport(0);
		
		var platformIO=ImGui.getPlatformIO();
		
		for(int n=0;n<platformIO.getViewportsSize();n++){
			var focused=d.isFocused();
			
			// Update mouse buttons
			if(focused){
				for(var key : RenderBackend.DisplayInterface.MouseKey.values()){
					io.setMouseDown(key.id, downKeys.contains(key));
				}
			}
			
			if(io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)){
				// Multi-viewport mode: mouse position in OS absolute coordinates (io.MousePos is (0,0) when the mouse is on the upper-left of the primary monitor)
				var windowX=d.getPositionX();
				var windowY=d.getPositionY();
				io.setMousePos(d.getMouseX()+windowX, d.getMouseY()+windowY);
			}else{
				// Single viewport mode: mouse position in client window coordinates (io.MousePos is (0,0) when the mouse is on the upper-left corner of the app window)
				io.setMousePos(d.getMouseX(), d.getMouseY());
			}
		}
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
