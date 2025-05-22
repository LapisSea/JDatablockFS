package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.DisplayManager;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiKey;
import imgui.internal.ImGuiContext;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;

import java.awt.Color;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;
import static org.lwjgl.glfw.GLFW.glfwGetTime;

public class VulkanDisplay implements AutoCloseable{
	
	private final GlfwWindow window;
	private final VulkanCore vkCore;
	
	private final CommandPool         cmdPool;
	private       List<CommandBuffer> graphicsBuffs;
	
	private final MsdfFontRender fontRender = new MsdfFontRender();
	
	private final ByteGridRender                byteGridRender = new ByteGridRender();
	private final ByteGridRender.RenderResource grid1Res       = new ByteGridRender.RenderResource();
	
	private final LineRenderer                lineRenderer = new LineRenderer();
	private final LineRenderer.RenderResource lineRes      = new LineRenderer.RenderResource();
	
	private final ImGUIRenderer imGUIRenderer = new ImGUIRenderer();
	
	private final Vector4f clearColor = new Vector4f(0, 0, 0, 1);
	
	private final ImGuiContext imGuiContext = ImGui.createContext();
	
	private record ImGUIKeyEvent(int code, boolean pressed){ }
	
	private final ConcurrentLinkedQueue<ImGUIKeyEvent> keyboardEvents = new ConcurrentLinkedQueue<>();
	
	public VulkanDisplay(){
		
		window = createWindow();
		window.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		window.size.register(this::onResizeEvent);
		
		initImGUI();
		
		try{
			vkCore = new VulkanCore("DFS debugger", window, VKPresentMode.IMMEDIATE);
			
			fontRender.init(vkCore);
			byteGridRender.init(vkCore);
			lineRenderer.init(vkCore);
			imGUIRenderer.init(vkCore);
			
			cmdPool = vkCore.device.createCommandPool(vkCore.renderQueueFamily, CommandPool.Type.NORMAL);
			graphicsBuffs = cmdPool.createCommandBuffers(vkCore.swapchain.images.size());
			
			byte[] bytes = new RawRandom(10).nextBytes(32*32);
			
			byteGridRender.record(
				grid1Res,
				new Matrix4f().translate(30, 30, 0).scale(15),
				32,
				bytes,
				List.of(
					new ByteGridRender.DrawRange(0, bytes.length/2, Color.green.darker()),
					new ByteGridRender.DrawRange(bytes.length/2, bytes.length, Color.RED.darker())
				),
				List.of(
					new ByteGridRender.IOEvent(6, 10, ByteGridRender.IOEvent.Type.WRITE),
					new ByteGridRender.IOEvent(8, 20, ByteGridRender.IOEvent.Type.READ)
				)
			);
			
			lineRenderer.record(lineRes, new Matrix4f(), List.of(
				new Geometry.PointsLine(
					Iters.rangeMap(0, 50, u -> u/50F*Math.PI)
					     .map(f -> new Vector2f((float)Math.sin(f)*100 + 150, -(float)Math.cos(f)*100 + 150))
					     .toList(),
					5, Color.ORANGE
				)
			));
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to init vulkan display", e);
		}
		
	}
	
	@SuppressWarnings("deprecation")
	private void initImGUI(){
		ImGui.setCurrentContext(imGuiContext);
		
		ImGuiIO io = ImGui.getIO();
		io.setKeyMap(ImGuiKey.Tab, GLFW.GLFW_KEY_TAB);
		io.setKeyMap(ImGuiKey.LeftArrow, GLFW.GLFW_KEY_LEFT);
		io.setKeyMap(ImGuiKey.RightArrow, GLFW.GLFW_KEY_RIGHT);
		io.setKeyMap(ImGuiKey.UpArrow, GLFW.GLFW_KEY_UP);
		io.setKeyMap(ImGuiKey.DownArrow, GLFW.GLFW_KEY_DOWN);
		io.setKeyMap(ImGuiKey.PageUp, GLFW.GLFW_KEY_PAGE_UP);
		io.setKeyMap(ImGuiKey.PageDown, GLFW.GLFW_KEY_PAGE_DOWN);
		io.setKeyMap(ImGuiKey.Home, GLFW.GLFW_KEY_HOME);
		io.setKeyMap(ImGuiKey.End, GLFW.GLFW_KEY_END);
		io.setKeyMap(ImGuiKey.Insert, GLFW.GLFW_KEY_INSERT);
		io.setKeyMap(ImGuiKey.Delete, GLFW.GLFW_KEY_DELETE);
		io.setKeyMap(ImGuiKey.Backspace, GLFW.GLFW_KEY_BACKSPACE);
		io.setKeyMap(ImGuiKey.Space, GLFW.GLFW_KEY_SPACE);
		io.setKeyMap(ImGuiKey.Enter, GLFW.GLFW_KEY_ENTER);
		io.setKeyMap(ImGuiKey.Escape, GLFW.GLFW_KEY_ESCAPE);
		io.setKeyMap(ImGuiKey.KeyPadEnter, GLFW.GLFW_KEY_KP_ENTER);
		io.setKeyMap(ImGuiKey.A, GLFW.GLFW_KEY_A);
		io.setKeyMap(ImGuiKey.C, GLFW.GLFW_KEY_C);
		io.setKeyMap(ImGuiKey.V, GLFW.GLFW_KEY_V);
		io.setKeyMap(ImGuiKey.X, GLFW.GLFW_KEY_X);
		io.setKeyMap(ImGuiKey.Y, GLFW.GLFW_KEY_Y);
		io.setKeyMap(ImGuiKey.Z, GLFW.GLFW_KEY_Z);
		
		try{
			var    f = io.getFonts();
			byte[] bb;
			try(var t = Objects.requireNonNull(DisplayManager.class.getResourceAsStream("/CourierPrime/Regular/font.ttf"))){
				bb = t.readAllBytes();
			}
			f.clearFonts();
			f.addFontFromMemoryTTF(bb, 16);
			f.build();
		}catch(Throwable e){
			throw new RuntimeException("Failed to load imgui font");
		}
		
		window.registryKeyboardKey.register(e -> {
			boolean pressed;
			switch(e.getType()){
				case DOWN -> pressed = true;
				case UP -> pressed = false;
				default -> { return; }
			}
			keyboardEvents.offer(new ImGUIKeyEvent(e.getKey(), pressed));
		});
	}
	
	private void render() throws VulkanCodeException{
		try{
			renderQueue();
		}catch(VulkanCodeException e){
			switch(e.code){
				case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> {
					handleResize();
				}
				default -> throw new RuntimeException("Failed to render frame", e);
			}
		}
	}
	
	private void renderQueue() throws VulkanCodeException{
		var swapchain = vkCore.swapchain;
		var queue     = vkCore.renderQueue;
		
		var frame = queue.nextFrame();
		
		queue.waitForFrameDone(frame);
		
		var index = queue.acquireNextImage(swapchain, frame);
		
		var buf = graphicsBuffs.get(frame);
		buf.reset();
		imguiUpdate();
		recordCommandBuffers(frame, index);
		
		queue.submitFrame(buf, frame);
		queue.present(swapchain, index, frame);
	}
	
	private double time;
	
	private void imguiUpdate(){
		var io    = ImGui.getIO();
		var mouse = window.mousePos;
		
		var extent = vkCore.swapchain.extent;
		io.setDisplaySize(extent.width, extent.height);
		
		final double currentTime = glfwGetTime();
		io.setDeltaTime(time>0.0? (float)(currentTime - time) : 1.0f/60.0f);
		time = currentTime;
		
		io.setMousePos(mouse.x(), mouse.y());
		
		io.setMouseDown(0, window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT));
		io.setMouseDown(1, window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
		io.setMouseDown(2, window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
		
		ImGUIKeyEvent event;
		while((event = keyboardEvents.poll()) != null){
			io.addKeyEvent(event.code, event.pressed);
		}
	}
	
	private boolean resizing;
	private Instant nextResizeWaitAttempt = Instant.now();
	private void handleResize() throws VulkanCodeException{
		resizing = true;
		try{
			vkCore.recreateSwapchainContext();
			if(vkCore.swapchain.images.size() != graphicsBuffs.size()){
				graphicsBuffs.forEach(CommandBuffer::destroy);
				graphicsBuffs = cmdPool.createCommandBuffers(vkCore.swapchain.images.size());
			}
			
			try{
				renderQueue();
			}catch(VulkanCodeException e2){
				switch(e2.code){
					case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> { }
					default -> throw new RuntimeException("Failed to render frame", e2);
				}
			}
		}finally{
			resizing = false;
		}
		
	}
	private void onResizeEvent(){
		var now = Instant.now();
		if(now.isBefore(nextResizeWaitAttempt)){
			return;
		}
		var end = now.plusMillis(50);
		if(!resizing){
			for(int i = 0; i<50; i++){
				if(resizing || Instant.now().isAfter(end)){
					break;
				}
				UtilL.sleep(0.5);
			}
		}
		for(int i = 0; i<100; i++){
			if(!resizing || Instant.now().isAfter(end)){
				break;
			}
			UtilL.sleep(0.5);
		}
		if(resizing){
			nextResizeWaitAttempt = Instant.now().plusMillis(1000);
		}
	}
	
	private void recordCommandBuffers(int frameID, int swapchainID) throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			VkClearColorValue clearColor = VkClearColorValue.malloc(stack);
			this.clearColor.get(clearColor.float32());
			
			var renderArea = new Rect2D(vkCore.swapchain.extent);
			
			var buf         = graphicsBuffs.get(frameID);
			var frameBuffer = vkCore.frameBuffers.get(swapchainID);
			
			buf.begin();
			try(var ignore = buf.beginRenderPass(vkCore.renderPass, frameBuffer, renderArea, clearColor)){
				
				renderAutoSizeByteGrid(frameID, buf);
				
				List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
				
				//testFontWave(sd);
				
				sd.add(new MsdfFontRender.StringDraw(
					100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
				sd.add(new MsdfFontRender.StringDraw(
					100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
				fontRender.render(buf, frameID, sd);
				
				
				renderDecimatedCurve(frameID, buf);
				
				ImGui.newFrame();
				
				if(ImGui.beginMainMenuBar()){
					if(ImGui.beginMenu("File")){
						ImGui.menuItem("Open");
						ImGui.menuItem("Exit");
						ImGui.endMenu();
					}
					ImGui.endMainMenuBar();
				}
				
				if(ImGui.begin("Hello")){
					ImGui.text("Hello from Java + Vulkan!");
					ImGui.text("It's working!! :DD");
					for(int i = 0; i<20; i++){
						ImGui.text("overflow test " + i);
					}
				}
				ImGui.end();
				ImGui.render();
				
				imGUIRenderer.submit(buf, frameID, ImGui.getDrawData());
			}
			buf.end();
		}
		
	}
	
	private void renderDecimatedCurve(int frameID, CommandBuffer buf) throws VulkanCodeException{
		var t = (System.currentTimeMillis())/500D;
		
		var controlPoints = Iters.of(3D, 2D, 1D, 4D, 5D).enumerate((i, s) -> new Vector2f(
			(float)Math.sin(t/s)*100 + 200*(i + 1),
			(float)Math.cos(t/s)*100 + 200
		)).toList();
		
		lineRenderer.record(lineRes, new Matrix4f(), Iters.concat1N(
			new Geometry.BezierCurve(controlPoints, 10, new Color(0.1F, 0.3F, 1, 0.6F), 30, 0.3),
			Iters.from(controlPoints)
			     .map(p -> new Geometry.PointsLine(List.of(p, p.add(0, 2, new Vector2f())), 2, Color.RED))
			     .toList()
		
		));
		lineRenderer.submit(buf, frameID, lineRes);
	}
	private void renderAutoSizeByteGrid(int frameID, CommandBuffer buf) throws VulkanCodeException{
		int count = 32*32;
		var space = vkCore.swapchain.extent;
		
		int bytesPerRow = 1;
		
		while(true){
			var byteSize   = space.width/(float)bytesPerRow;
			var rows       = Math.ceilDiv(count, bytesPerRow);
			var rowsHeight = rows*byteSize;
			if(rowsHeight>=space.height){
				bytesPerRow++;
			}else{
				break;
			}
		}
		var byteSize = space.width/(float)bytesPerRow;
		
		byteGridRender.record(
			grid1Res, frameID,
			new Matrix4f().translate(0, 0, 0)
			              .scale(byteSize),
			bytesPerRow
		);
		byteGridRender.submit(buf, frameID, grid1Res);
	}
	
	private static void testFontWave(List<MsdfFontRender.StringDraw> sd){
		var pos = 0F;
		for(int i = 0; i<40; i++){
			float size = 1 + (i*i)*0.2F;
			
			var t = (System.currentTimeMillis())/500D;
			var h = (float)Math.sin(t + pos/(10 + i*3))*50;
			sd.add(new MsdfFontRender.StringDraw(size, Color.GREEN.darker(),
			                                     "a", 20 + pos, 70 + 360 - h));
			sd.add(new MsdfFontRender.StringDraw(size, Color.WHITE,
			                                     "a", 20 + pos, 70 + 360 - h, 1, 2F));
			pos += size*0.4F + 2;
		}
	}
	
	private GlfwWindow createWindow(){
		var win = new GlfwWindow();
		win.title.set("DFS visual debugger");
		win.size.set(800, 600);
		win.centerWindow();
		
		var winFile = new File("winRemember.json");
		win.loadState(winFile);
		win.autoHandleStateSaving(winFile);
		
		win.init(i -> i.withVulkan(v -> v.withVersion(VulkanCore.API_VERSION_MAJOR, VulkanCore.API_VERSION_MINOR)).resizeable(true));
		Thread.ofVirtual().start(() -> win.setIcon(createVulkanIcon(128, 128)));
		return win;
	}
	
	public void run(){
		var t = Thread.ofPlatform().name("render").start(() -> {
			boolean first = true;
			while(!window.shouldClose()){
				try{
					render();
					if(first){
						first = false;
						vkCore.renderQueue.waitIdle();
						window.show();
					}
				}catch(Throwable e){
					e.printStackTrace();
					requestClose();
				}
			}
		});
		while(!window.shouldClose()){
			try{
				window.pollEvents();
			}catch(Throwable e){
				e.printStackTrace();
				requestClose();
			}
			try{
				Thread.sleep(5);
			}catch(InterruptedException e){ requestClose(); }
		}
		try{
			t.join();
		}catch(InterruptedException e){
			throw new RuntimeException(e);
		}
	}
	private void requestClose(){
		window.requestClose();
	}
	
	@Override
	public void close() throws VulkanCodeException{
		window.hide();
		
		vkCore.device.waitIdle();
		
		fontRender.destroy();
		
		grid1Res.destroy();
		byteGridRender.destroy();
		
		lineRes.destroy();
		lineRenderer.destroy();
		
		imGUIRenderer.destroy();
		
		graphicsBuffs.forEach(CommandBuffer::destroy);
		cmdPool.destroy();
		
		vkCore.close();
		window.destroy();
	}
}
