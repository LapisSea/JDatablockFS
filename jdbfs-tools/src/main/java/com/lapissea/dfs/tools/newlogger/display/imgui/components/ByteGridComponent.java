package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.tools.DrawUtils.Range;
import com.lapissea.dfs.tools.newlogger.SessionSetView;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.MultiRendererBuffer;
import com.lapissea.dfs.tools.newlogger.display.renderers.grid.GridScene;
import com.lapissea.dfs.tools.newlogger.display.renderers.grid.PrimitiveBuffer;
import com.lapissea.dfs.tools.newlogger.display.renderers.grid.RangeMessageSpace;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import org.joml.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MultiRendererBuffer multiRenderer;
	
	
	private final List<String> messages;
	
	private       SessionSetView.FrameData frameData;
	private final SettingsUIComponent      uiSettings;
	
	private Match<GridUtils.ByteGridSize> lastGridSize = Match.empty();
	
	private static final ExecutorService SCENE_BUILD_POOL = Executors.newSingleThreadExecutor();
	
	private CompletableFuture<GridScene> scene;
	private GridScene                    oldScene;
	
	
	public ByteGridComponent(VulkanCore core, ImBoolean open, SettingsUIComponent uiSettings, List<String> messages) throws VulkanCodeException{
		super(core, open, uiSettings.byteGridSampleEnumIndex);
		this.uiSettings = uiSettings;
		this.messages = messages;
		
		clearColor.set(0.4, 0.4, 0.4, 1);
		
		multiRenderer = new MultiRendererBuffer(core);
	}
	
	public void setDisplayData(SessionSetView.FrameData src) throws IOException{
		frameData = src;
		if(scene != null){
			oldScene = scene.isDone()? scene.join() : null;
		}
		scene = null;
	}
	
	@Override
	protected boolean needsRerender(){ return true; }
	
	@Override
	protected void renderBackbuffer(DeviceGC deviceGC, CommandBuffer cmdBuffer, Extent2D viewSize) throws VulkanCodeException{
		
		if(mouseOver()){
			var delta = 0;
			if(ImGui.getIO().getMouseWheel()>0) delta--;
			if(ImGui.getIO().getMouseWheel()<0) delta++;
			
			if(delta != 0){
				var ses = uiSettings.currentSessionView();
				if(ses.isPresent()){
					uiSettings.currentSessionRange.frameDelta(ses.get().frameCount(), delta);
					uiSettings.vulkanDisplay.setFrameData(ses, uiSettings.currentSessionRange.getCurrentFrame());
				}
			}
		}
		
		multiRenderer.reset();
		GridUtils.ByteGridSize size;
		try{
			size = recordBackbuffer(deviceGC, viewSize);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		multiRenderer.submit(deviceGC, size, viewSize, cmdBuffer);
	}
	
	protected GridUtils.ByteGridSize recordBackbuffer(DeviceGC deviceGC, Extent2D viewSize) throws VulkanCodeException, IOException{
		
		{
			boolean errorMode = false;//TODO should error mode even be used? Bake whole database once instead of at render time?
			var     color     = errorMode? Color.RED.darker() : new Color(0xDBFFD700, true);
			
			multiRenderer.renderMesh(GridUtils.backgroundDots(viewSize, color, ImGui.getWindowDpiScale()));
		}
		
		long byteCount = getFrameSize(frameData);
		
		if(byteCount == 0){
			renderFullScreenMessage(viewSize, "No data!");
			scene = null;
			oldScene = null;
			lastGridSize = Match.empty();
			return new GridUtils.ByteGridSize(1, 1, viewSize);
		}
		
		
		if(ImGui.isKeyPressed(ImGuiKey.R)){
			lastGridSize = Match.empty();
		}
		
		var gridSize = GridUtils.ByteGridSize.compute(viewSize, byteCount, lastGridSize);
		lastGridSize = Match.of(gridSize);
		
		if(scene == null){
			var fd = frameData;
			scene = CompletableFuture.supplyAsync(() -> {
				if(fd != frameData){
					return null;
				}
				var scene = new GridScene(new PrimitiveBuffer.SimpleBuffer(multiRenderer.getFontRender()), frameData, gridSize);
				try{
					scene.build();
				}catch(IOException e){
					throw new RuntimeException("Failed to build scene", e);
				}
				return scene;
			}, SCENE_BUILD_POOL);
			scene.thenRun(() -> oldScene = null);
		}
		
		if(!scene.isDone() && oldScene == null){
			renderFullScreenMessage(viewSize, "Data building...");
			return new GridUtils.ByteGridSize(1, 1, viewSize);
		}
		
		boolean   oldData = false;
		GridScene scene;
		if(this.scene.isDone()){
			scene = this.scene.join();
			
			if(!gridSize.equals(scene.gridSize)){
				oldScene = scene;
				this.scene = null;
			}
			
		}else if(oldScene != null){
			scene = oldScene;
			oldData = true;
		}else{
			renderFullScreenMessage(viewSize, "Data building...");
			return new GridUtils.ByteGridSize(1, 1, viewSize);
		}
		
		
		for(PrimitiveBuffer.SimpleBuffer.TokenSet token : ((PrimitiveBuffer.SimpleBuffer)scene.buffer).tokens){
			switch(token){
				case PrimitiveBuffer.SimpleBuffer.TokenSet.ByteEvents(var tokens) -> {
					for(PrimitiveBuffer.SimpleBuffer.ByteToken t : tokens){
						multiRenderer.renderBytes(deviceGC, t.dataOffset(), t.data(), t.ranges(), t.ioEvents());
					}
				}
				case PrimitiveBuffer.SimpleBuffer.TokenSet.Lines(var lines) -> {
					multiRenderer.renderLines(lines);
				}
				case PrimitiveBuffer.SimpleBuffer.TokenSet.Meshes(var meshes) -> {
					for(Geometry.IndexedMesh mesh : meshes){
						multiRenderer.renderMesh(mesh);
					}
				}
				case PrimitiveBuffer.SimpleBuffer.TokenSet.Strings(var strings) -> {
					multiRenderer.renderFont(strings);
				}
			}
		}
		
		if(GridUtils.calcByteIndex(gridSize, mouseX(), mouseY(), byteCount, 1) instanceof Some(var p)){
			for(var message : scene.messages.collect(p)){
				switch(message.hoverEffect()){
					case RangeMessageSpace.HoverEffect.None ignore -> { }
					case RangeMessageSpace.HoverEffect.Outline(Color color, float lineWidth) -> {
						outlineByteRange(scene.gridSize, color, message.range(), lineWidth);
					}
				}
				messages.add(message.message());
			}
			
			int b = -1;
			try{
				b = frameData.contents().ioMapAt(p, ContentReader::readUnsignedInt1);
			}catch(IOException ignored){ }
			messages.add("Hovered byte at " + p + ": " + (b == -1? "Unable to read byte" : b + "/" + (char)b));
			
			multiRenderer.renderLines(
				GridUtils.outlineByteRange(Color.WHITE, gridSize, new Range(p, p + 1), 1.5F)
			);
		}
		if(oldData) multiRenderer.renderFont(new MsdfFontRender.StringDraw(
			20*ImGui.getWindowDpiScale(),
			Color.DARK_GRAY, "Old data...",
			2, viewSize.height - 2
		));
		
		return scene.gridSize;
	}
	private void outlineByteRange(GridUtils.ByteGridSize gridSize, Color color, Range range, float lineWidth){
		var lines = GridUtils.outlineByteRange(color, gridSize, range, lineWidth);
		multiRenderer.renderLines(lines);
	}
	
	private void renderFullScreenMessage(Extent2D viewSize, String str){
		int w         = viewSize.width, h = viewSize.height;
		var fontScale = Math.min(h*0.8F, w/(str.length()*0.8F));
		
		if(GridUtils.stringDrawIn(
			multiRenderer.getFontRender(), str, new GridUtils.Rect(w, h), Color.LIGHT_GRAY, fontScale, false
		) instanceof Some(var draw)){
			multiRenderer.renderFont(draw, draw.withOutline(new Color(0, 0, 0, 0.5F), 1.5F));
		}
	}
	
	private static long getFrameSize(SessionSetView.FrameData frameData){
		try{
			return (frameData == null? 0 : frameData.contents().getIOSize());
		}catch(IOException e){
			throw new RuntimeException("Failed to get data size", e);
		}
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		super.unload(tScope);
		multiRenderer.destroy();
	}
	
	private void testCurve(){
		var t = (System.currentTimeMillis())/500D;
		
		var controlPoints = Iters.of(3D, 2D, 1D, 4D, 5D).enumerate((i, s) -> new Vector2f(
			(float)Math.sin(t/s)*100 + 200*(i + 1),
			(float)Math.cos(t/s)*100 + 200
		)).toList();
		multiRenderer.renderLines(List.of(new Geometry.BezierCurve(controlPoints, 10, new Color(0.1F, 0.3F, 1, 0.6F), 30, 0.3)));
	}
}
