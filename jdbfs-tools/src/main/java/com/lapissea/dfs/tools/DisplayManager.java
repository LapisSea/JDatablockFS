package com.lapissea.dfs.tools;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigUtils;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.MemFrame;
import com.lapissea.dfs.tools.render.G2DBackend;
import com.lapissea.dfs.tools.render.ImGuiUtils;
import com.lapissea.dfs.tools.render.OpenGLBackend;
import com.lapissea.dfs.tools.render.RenderBackend;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.util.MathUtil;
import com.lapissea.util.Rand;
import com.lapissea.util.UtilL;
import com.lapissea.vec.Vec2iFinal;
import com.lapissea.vec.interf.IVec2iR;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiConfigFlags;

import java.awt.Color;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.lapissea.dfs.config.ConfigDefs.CONFIG_PROPERTY_PREFIX;
import static com.lapissea.dfs.logging.Log.log;
import static org.lwjgl.glfw.GLFW.*;

public class DisplayManager implements DataLogger{
	
	private static final boolean DO_JIT_WARMUP = ConfigUtils.configBoolean(CONFIG_PROPERTY_PREFIX + "tools.jitWarmup", true);
	
	static{
		ImGuiUtils.load();
	}
	
	private boolean destroyRequested = false;
	
	private final RenderBackend          renderer;
	private final RenderBackend.Buffered gridBuff;
	
	private final SessionHost        sessionHost;
	private final DataRenderer.Split splitRenderer;
	
	private boolean titleDirty = true;
	
	public DisplayManager(boolean blockLogTillDisplay){
		sessionHost = new SessionHost(blockLogTillDisplay);
		
		renderer = createBackend();
		gridBuff = renderer.buffer();
		
		var graphRenderer = new DataRenderer.Lazy(() -> new GraphRenderer(renderer));
		var gridRenderer  = new DataRenderer.Lazy(() -> new BinaryGridRenderer(RenderBackend.DRAW_DEBUG? renderer : gridBuff));
		
		splitRenderer = new DataRenderer.Split(List.of(gridRenderer, graphRenderer));
		
		Runnable updateTitle = () -> {
			titleDirty = true;
			splitRenderer.markDirty();
			renderer.markFrameDirty();
		};
		sessionHost.activeFrame.register(i -> updateTitle.run());
		sessionHost.activeSession.register(ses -> updateTitle.run());
		renderer.start(this::start);
		updateTitle.run();
	}
	
	private RenderBackend createBackend(){
		var fails = new LinkedList<Throwable>();
		
		try{
			return new OpenGLBackend();
		}catch(Throwable e){
			fails.add(e);
		}
		try{
			return new G2DBackend();
		}catch(Throwable e){
			fails.add(e);
		}
		
		var e = new RuntimeException("Failed to create render display");
		fails.forEach(e::addSuppressed);
		throw e;
	}
	
	private final EnumSet<RenderBackend.DisplayInterface.MouseKey> justPressedKeys = EnumSet.noneOf(RenderBackend.DisplayInterface.MouseKey.class);
	private final EnumSet<RenderBackend.DisplayInterface.MouseKey> lastDownKeys    = EnumSet.noneOf(RenderBackend.DisplayInterface.MouseKey.class);
	private final EnumSet<RenderBackend.DisplayInterface.MouseKey> downKeys        = EnumSet.noneOf(RenderBackend.DisplayInterface.MouseKey.class);
	private void start(){
		
		var display = renderer.getDisplay();
		display.registerMouseButton(e -> {
			justPressedKeys.add(e.click());
			switch(e.type()){
				case DOWN -> downKeys.add(e.click());
				case UP -> downKeys.remove(e.click());
			}
			renderer.markFrameDirty();
		});
		
		display.registerMouseScroll(delta -> {
			
			Thread.startVirtualThread(() -> {
				int     steps  = 10;
				float[] deltas = new float[steps];
				for(int i = 0; i<deltas.length; i++){
					var val = steps - i;
					deltas[i] = (float)Math.pow(val, 3);
				}
				
				float sum = 0;
				for(float v : deltas){
					sum += v;
				}
				for(int i = 0; i<deltas.length; i++){
					deltas[i] /= sum;
					deltas[i] *= delta;
				}
				
				for(float v : deltas){
					ImGui.getIO().setMouseWheel(ImGui.getIO().getMouseWheel() + v);
					renderer.markFrameDirty();
					UtilL.sleep(16);
				}
			});
			
			if(ImGui.getIO().getWantCaptureMouse()){
				renderer.markFrameDirty();
				return;
			}
			
			splitRenderer.getDisplayedSession().ifPresent(ses -> {
				ses.setFrame(Math.max(0, splitRenderer.getFramePos() - delta));
				splitRenderer.markDirty();
			});
		});
		
		display.registerMouseMove(() -> {
			renderer.markFrameDirty();
			
			if(!display.isMouseKeyDown(RenderBackend.DisplayInterface.MouseKey.LEFT)) return;
			if(ImGui.getIO().getWantCaptureMouse()) return;
			
			splitRenderer.getDisplayedSession().ifPresent(ses -> {
				float percent = MathUtil.snap((display.getMouseX() - 10F)/(display.getWidth() - 20F), 0, 1);
				ses.setFrame(Math.round((ses.frames.size() - 1)*percent));
			});
			renderer.markFrameDirty();
			splitRenderer.markDirty();
		});
		
		display.registerDisplayResize(() -> {
			sessionHost.cleanUpSessions();
			splitRenderer.notifyResize();
			renderer.markFrameDirty();
			splitRenderer.markDirty();
			renderer.runLater(() -> {
				if(renderer.notifyDirtyFrame()){
					doRender();
				}
			});
		});
		
		display.registerKeyboardButton(e -> {
			sessionHost.cleanUpSessions();
			if(e.type() != RenderBackend.DisplayInterface.ActionType.DOWN && e.key() == GLFW_KEY_LEFT_ALT){
				splitRenderer.next();
			}
			if(e.type() != RenderBackend.DisplayInterface.ActionType.DOWN && splitRenderer.getDisplayedSession().isPresent()){
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
			if(e.key() == GLFW_KEY_LEFT || e.key() == GLFW_KEY_A) delta = -1;
			else if(e.key() == GLFW_KEY_RIGHT || e.key() == GLFW_KEY_D) delta = 1;
			else return;
			if(e.type() == RenderBackend.DisplayInterface.ActionType.UP) return;
			splitRenderer.markDirty();
			splitRenderer.getDisplayedSession().ifPresent(ses -> ses.setFrame(splitRenderer.getFramePos() + delta));
		});
		
		try{
			if(!destroyRequested){
				int  jitWarmup     = DO_JIT_WARMUP? 0 : Integer.MAX_VALUE;
				long lastFrameTime = 0;
				while(display.isOpen()){
					
					sessionHost.cleanUpSessions();
					var activeSession = sessionHost.activeSession.get();
					
					if(activeSession.isPresent()){
						if(activeSession.get().framePos.get() == -1){
							renderer.markFrameDirty();
						}
						
						if(jitWarmup<150 && Rand.b(0.1F)){
							jitWarmup++;
							renderer.markFrameDirty();
						}
					}else{
						if(jitWarmup<20 && Rand.b(0.1F)){
							jitWarmup++;
							renderer.markFrameDirty();
						}
					}
					
					if(!splitRenderer.getDisplayedSession().equals(activeSession)){
						splitRenderer.setDisplayedSession(activeSession);
						ifFrame(frame -> splitRenderer.notifyResize());
					}
					if(destroyRequested){
						destroyRequested = false;
						display.requestClose();
					}
//					long tim=System.currentTimeMillis();
//					if(tim-lastFrameTime>1000){
//						lastFrameTime=tim;
//						renderer.markFrameDirty();
//					}
					
					if(titleDirty){
						titleDirty = false;
						
						var f = sessionHost.activeFrame.get();
						renderer.getDisplay().setTitle(
							"Binary display - frame: " + (f == -1? "NaN" : f) +
							(f>0? sessionHost.activeSession.get()
							                               .map(s -> {
								                               try{
									                               var d = Duration.ofNanos(
										                               s.frames.get(f).memData().timeDelta() -
										                               s.frames.get(f - 1).memData().timeDelta()
									                               );
									                               var format = NumberFormat.getInstance();
									                               format.setMinimumFractionDigits(2);
									                               format.setMaximumFractionDigits(2);
									                               if(d.toMillis()>500) return format.format(d.toMillis()/1000D) + "S";
									                               if(d.toNanos()>10_000) return format.format(d.toNanos()/1000_000D) + "ms";
									                               return d.toNanos() + "ns";
								                               }catch(IndexOutOfBoundsException e){
									                               return "ERR";
								                               }
							                               })
							                               .map(t -> ", Time: " + t)
							                               .orElse("") : "") +
							sessionHost.activeSession.get().map(s -> " - Session: " + s.getName()).orElse("")
						);
					}
					
					if(renderer.notifyDirtyFrame() || splitRenderer.isDirty()){
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
	
	private List<DataRenderer.HoverMessage> hover = List.of();
	
	private void doRender(){
		updateImgui();
		
		renderer.preRender();
		
		var m = ImGui.getIO().getWantCaptureMouse();
		if(RenderBackend.DRAW_DEBUG || !m || splitRenderer.isDirty()){
			gridBuff.clear();
			hover = splitRenderer.render();
		}
		
		gridBuff.draw();
		
		ImGui.newFrame();
		drawImgui();
		ImGui.render();
		
		renderer.postRender();
		
		splitRenderer.getDisplayedSession().ifPresent(ses -> {
			if(ses.framePos.get() == -1){
				synchronized(ses.frames){
					ses.setFrame(ses.frames.size() - 1);
				}
			}
		});
		
		if(!lastDownKeys.equals(downKeys)){
			renderer.markFrameDirty();
			renderer.runLater(renderer::markFrameDirty);
			lastDownKeys.clear();
			lastDownKeys.addAll(downKeys);
		}
	}
	
	private final boolean[] cfWrap = {false}, skipImpl = {true};
	private void drawImgui(){
		ifFrame(memFrame -> {
			ImGui.begin("Current Frame:");
			
			if(!ImGui.isWindowCollapsed()){
				clampWindow();
				
				sessionHost.activeSession.get().ifPresent(s -> {
					ImGui.text("Session: " + s.getName());
					ImGui.text("Frame: " + (sessionHost.activeFrame.get() + 1) + "/" + s.frames.size());
				});
				
				ImGui.newLine();
				
				ImGui.text("Write event stack trace:");
				ImGui.separator();
				
				showTrace(memFrame.e(), cfWrap, skipImpl);
			}
			
			ImGui.end();
		});
		splitRenderer.getDisplayedSession().map(s -> s.frames).ifPresent(frames -> {
			if(frames.isEmpty()) return;
			SessionHost.ParsedFrame f;
			try{
				f = frames.get(splitRenderer.getFramePos()).parsed();
			}catch(IndexOutOfBoundsException ignored){
				return;
			}
			var err = f.displayError;
			if(err == null) return;
			
			
			ImGui.begin("Display Error:");
			
			clampWindow();
			
			ImGui.text("Breakdown:");
			ImGui.separator();
			var msg = DrawUtils.errorToMessage(err);
			ImGui.text(msg);
			
			ImGui.newLine();
			ImGui.text("Full stack trace:");
			ImGui.separator();
			showTrace(Utils.errToStackTrace(err), cfWrap, skipImpl);
			
			ImGui.end();
		});
		
		
		if(!hover.isEmpty()){
			ImGui.begin("Hover data:");
			for(var msg : hover){
				for(Object object : msg.data()){
					ImGui.sameLine();
					if(object instanceof String str){
						var col = msg.color();
						if(col == null) col = new Color(200, 255, 200);
						ImGui.textColored(col.getRed(), col.getGreen(), col.getBlue(), 255, str);
					}else{
						switch(object){
							case IOInstance<?> inst -> {
								var str = inst.toString(false, "{\n\t", "\n}", ": ", ",\n\t");
								
								if(str == null) str = "";
								var col = msg.color();
								if(col == null) col = new Color(100, 100, 255);
								ImGui.textColored(col.getRed(), col.getGreen(), col.getBlue(), 255, str);
							}
							case DataRenderer.FieldVal<?> inst -> {
								String str;
								try{
									str = inst.instanceToString(new StringifySettings(
										false, "{\n\t", "\n}", ": ", ",\n\t"
									)).orElse("");
								}catch(Throwable e){
									str = "<CORRUPT :" + e + ">";
								}
								
								var col = msg.color();
								if(col == null) col = new Color(100, 255, 100);
								ImGui.textColored(col.getRed(), col.getGreen(), col.getBlue(), 255, str);
							}
							default -> {
								var str = Objects.toString(object);
								
								if(str == null) str = "";
								var col = msg.color();
								if(col == null) col = Color.WHITE;
								ImGui.textColored(col.getRed(), col.getGreen(), col.getBlue(), 255, str);
							}
						}
					}
				}
				ImGui.separator();
			}
			ImGui.end();
		}
		
		lastSiz = new Vec2iFinal(renderer.getDisplay().getWidth(), renderer.getDisplay().getHeight());
	}
	
	private void clampWindow(){
		if(lastSiz.equals(renderer.getDisplay().getWidth(), renderer.getDisplay().getHeight())){
			return;
		}
		
		var siz = ImGui.getWindowSize();
		var pos = ImGui.getWindowPos();
		var io  = ImGui.getIO();
		var w   = io.getDisplaySizeX();
		
		var right = siz.x + pos.x;
		if(right>w){
			if(pos.x>0.0001F){
				pos.x = Math.max(0, pos.x - (right - w));
				ImGui.setWindowPos(pos.x, pos.y);
				renderer.markFrameDirty();
				clampWindow();
				return;
			}
		}
		
		if(Math.abs(pos.x)<0.0001F){
			var dist = Math.abs(siz.x - lastSiz.x());
			if(dist<0.0001F){
				ImGui.setWindowSize(w, siz.y);
				renderer.markFrameDirty();
				return;
			}
		}
		var diff = siz.x - w;
		if(diff>0){
			ImGui.setWindowSize(w, siz.y);
			renderer.markFrameDirty();
		}
	}
	
	private void showTrace(String trace, boolean[] cfWrap, boolean[] skipImpl){
		if(ImGui.button("Wrap")){
			cfWrap[0] = !cfWrap[0];
		}
		ImGui.sameLine();
		ImGui.text(cfWrap[0]? "Y" : "N");
		
		if(ImGui.button("Skip impl package")){
			skipImpl[0] = !skipImpl[0];
		}
		ImGui.sameLine();
		ImGui.text(skipImpl[0]? "Y" : "N");
		
		if(ImGui.button("Print to console")){
			log(trace);
		}
		
		var style   = ImGui.getStyle();
		var padding = style.getItemSpacing();
		style.setItemSpacing(0, 0);
		
		ImGui.newLine();
		
		Consumer<String> pushLine = line -> {
			{
				var     at = "\tat ";
				Pattern p  = Pattern.compile(at + ".+/");
				Matcher m  = p.matcher(line);
				if(m.find()){
					var module = line.substring(m.start() + at.length(), m.end());
					ImGui.sameLine();
					ImGui.textColored(255, 100, 100, 255, line.substring(0, at.length()));
					ImGui.sameLine();
					ImGui.textColored(255, 200, 200, 255, module);
					line = line.substring(m.end());
				}
			}
			
			String loc = "";
			
			Pattern p = Pattern.compile("\\(.+:[0-9]+\\)$");
			Matcher m = p.matcher(line);
			if(m.find()){
				loc = line.substring(m.start() + 1, m.end() - 1);
				line = line.substring(0, m.start());
			}
			
			String funct = "";
			if(!loc.isEmpty()){
				funct = line.substring(line.lastIndexOf('.') + 1);
				line = line.substring(0, line.length() - funct.length());
			}
			
			
			for(int i = 0; i<line.length(); i++){
				char c = line.charAt(i);
				ImGui.sameLine();
				var cs = "" + c;
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
		
		var w            = ImGui.getWindowContentRegionMaxX() - 10;
		var siz          = new ImVec2();
		int skippedLines = 0;
		for(var e : trace.split("\n")){
			if(skipImpl[0] && e.contains(".impl.") && !e.contains("Transaction")){
				skippedLines++;
				continue;
			}
			if(skippedLines>0){
				ImGui.sameLine();
				pushLine.accept("\t... " + skippedLines + " lines");
				skippedLines = 0;
			}
			if(cfWrap[0]){
				String str = "";
				for(int i = 0; i<e.length(); i++){
					var newStr = str + e.charAt(i);
					ImGui.calcTextSize(siz, newStr);
					if(siz.x>w){
						ImGui.newLine();
						pushLine.accept(str);
						str = "";
						i--;
						continue;
					}
					str = newStr;
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
	
	private double  time = 0.0;
	private IVec2iR lastSiz;
	
	private void updateImgui(){
		int w = renderer.getDisplay().getWidth(), h = renderer.getDisplay().getHeight();
		if(lastSiz == null) lastSiz = new Vec2iFinal(w, h);
		
		var io = ImGui.getIO();
		io.setDisplaySize(w, h);
		
		final double currentTime = glfwGetTime();
		io.setDeltaTime(time>0.0? (float)(currentTime - time) : 1.0f/60.0f);
		time = currentTime;
		
		updateMousePosAndButtons();
	}
	
	private void updateMousePosAndButtons(){
		var io = ImGui.getIO();
		var d  = renderer.getDisplay();
		
		for(var key : RenderBackend.DisplayInterface.MouseKey.values()){
			io.setMouseDown(key.id, justPressedKeys.contains(key) || downKeys.contains(key));
		}
		justPressedKeys.clear();
		
		var mousePosBackup = new ImVec2();
		
		io.getMousePos(mousePosBackup);
		io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE);
		io.setMouseHoveredViewport(0);
		
		var platformIO = ImGui.getPlatformIO();
		
		for(int n = 0; n<platformIO.getViewportsSize(); n++){
			var focused = d.isFocused();
			
			// Update mouse buttons
			if(focused){
				for(var key : RenderBackend.DisplayInterface.MouseKey.values()){
					io.setMouseDown(key.id, downKeys.contains(key));
				}
			}
			
			if(io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)){
				// Multi-viewport mode: mouse position in OS absolute coordinates (io.MousePos is (0,0) when the mouse is on the upper-left of the primary monitor)
				var windowX = d.getPositionX();
				var windowY = d.getPositionY();
				io.setMousePos(d.getMouseX() + windowX, d.getMouseY() + windowY);
			}else{
				// Single viewport mode: mouse position in client window coordinates (io.MousePos is (0,0) when the mouse is in the upper-left corner of the app window)
				io.setMousePos(d.getMouseX(), d.getMouseY());
			}
		}
	}
	
	private void ifFrame(Consumer<MemFrame> o){
		splitRenderer.getDisplayedSession().map(s -> s.frames).ifPresent(frames -> {
			synchronized(frames){
				if(frames.isEmpty()) return;
				try{
					o.accept(frames.get(splitRenderer.getFramePos()).memData());
				}catch(IndexOutOfBoundsException ignored){ }
			}
		});
	}
	
	
	@Override
	public DataLogger.Session getSession(String name){
		return sessionHost.getSession(name);
	}
	@Override
	public void destroy(){
		destroyRequested = true;
		sessionHost.destroy();
		splitRenderer.setDisplayedSession(Optional.empty());
		renderer.markFrameDirty();
	}
	@Override
	public boolean isActive(){
		return sessionHost.isActive();
	}
}
