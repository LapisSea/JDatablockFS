package com.lapissea.dfs.inspect.display.imgui.components;

import com.lapissea.dfs.inspect.SessionSetView;
import com.lapissea.dfs.inspect.display.Col;
import com.lapissea.dfs.inspect.display.DeviceGC;
import com.lapissea.dfs.inspect.display.TextureRegistry;
import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.grid.GridRect;
import com.lapissea.dfs.inspect.display.grid.GridScene;
import com.lapissea.dfs.inspect.display.grid.GridUtils;
import com.lapissea.dfs.inspect.display.grid.RangeMessageSpace;
import com.lapissea.dfs.inspect.display.primitives.Path;
import com.lapissea.dfs.inspect.display.renderers.CountingPrimitiveBuffer;
import com.lapissea.dfs.inspect.display.renderers.CutoffPrimitiveBuffer;
import com.lapissea.dfs.inspect.display.renderers.MergingPrimitiveBuffer;
import com.lapissea.dfs.inspect.display.renderers.MsdfFontRender;
import com.lapissea.dfs.inspect.display.renderers.MultiRendererBuffer;
import com.lapissea.dfs.inspect.display.renderers.PrimitiveBuffer;
import com.lapissea.dfs.inspect.display.vk.CommandBuffer;
import com.lapissea.dfs.inspect.display.vk.VulkanCore;
import com.lapissea.dfs.inspect.display.vk.wrap.Extent2D;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.tools.DrawUtils.Range;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import org.joml.Vector2f;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MultiRendererBuffer                renderer;
	private final MultiRendererBuffer.RenderResource staticRes  = new MultiRendererBuffer.RenderResource();
	private       MultiRendererBuffer.RenderToken    staticGpuCall;
	private final MultiRendererBuffer.RenderResource dynamicRes = new MultiRendererBuffer.RenderResource();
	private       MultiRendererBuffer.TokenBuilder   dynamicTokens;
	private       Object                             shownSceneId;
	
	private final List<String> messages;
	
	private       SessionSetView.FrameData frameData;
	private final SettingsUIComponent      uiSettings;
	
	private Match<GridUtils.ByteGridSize> lastGridSize = Match.empty();
	
	private static final ThreadPoolExecutor SCENE_BUILD_POOL = new ThreadPoolExecutor(
		3, 3,
		0L, TimeUnit.MILLISECONDS,
		new LinkedBlockingQueue<>()
	);
	
	private GridScene scene;
	private GridScene oldScene;
	private boolean   currentBuildFlag;
	
	public ByteGridComponent(VulkanCore core, ImBoolean open, SettingsUIComponent uiSettings, List<String> messages) throws VulkanCodeException{
		super(core, open, uiSettings.byteGridSampleEnumIndex);
		this.uiSettings = uiSettings;
		this.messages = messages;
		
		clearColor.set(0.4, 0.4, 0.4, 1);
		
		renderer = new MultiRendererBuffer(core);
	}
	
	private record BuildTask(
		SessionSetView.FrameData frameData, GridUtils.ByteGridSize gridSize, boolean merge, PrimitiveBuffer.FontRednerer fontRednerer, long cutoff
	) implements Supplier<GridScene>{
		
		@Override
		public GridScene get(){
			PrimitiveBuffer buff = new MergingPrimitiveBuffer(fontRednerer, gridSize, merge);
			if(cutoff>0){
				buff = new CutoffPrimitiveBuffer(buff, cutoff);
			}
			var scene = new GridScene(buff, frameData, gridSize);
			try{
				scene.build();
			}catch(IOException e){
				throw new RuntimeException("Failed to build scene", e);
			}
			return scene;
		}
	}
	
	private void clearStaticRenderer(){
		staticRes.reset();
		staticGpuCall = null;
		shownSceneId = null;
	}
	
	private synchronized void clearScene(){
		oldScene = null;
		scene = null;
		currentBuildFlag = false;
		clearStaticRenderer();
	}
	private synchronized void retireScene(){
		if(scene != null){
			oldScene = scene;
		}
		scene = null;
		currentBuildFlag = false;
	}
	private synchronized void pushScene(GridScene newScene){
		oldScene = null;
		scene = newScene;
		currentBuildFlag = false;
	}
	
	public synchronized void setDisplayData(SessionSetView.FrameData src) throws IOException{
		frameData = src;
		retireScene();
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
		
		dynamicTokens = new MultiRendererBuffer.TokenBuilder(renderer.getFontRender());
		GridUtils.ByteGridSize size;
		try{
			size = recordBackbuffer(deviceGC, viewSize);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		dynamicRes.reset();
		var rToken = renderer.upload(dynamicRes, dynamicTokens, deviceGC);
		renderer.submit(size, size.windowSize(), rToken, cmdBuffer);
	}
	
	private final ImBoolean merge = new ImBoolean(true), showBoundingBoxes = new ImBoolean();
	private final long[] cutoff = new long[1], cutoffMax = new long[1];
	
	protected GridUtils.ByteGridSize recordBackbuffer(DeviceGC deviceGC, Extent2D viewSize) throws VulkanCodeException, IOException{
		
		{
			boolean errorMode = false;//TODO should error mode even be used? Bake whole database once instead of at render time?
			var     col       = errorMode? Col.RED.darker() : Col.rgba(0xDB_00_D7_FF);
			
			dynamicTokens.renderMesh(GridUtils.backgroundDots(viewSize, col, ImGui.getWindowDpiScale()));
		}
		
		long byteCount = getFrameSize(frameData);
		
		if(byteCount == 0){
			clearScene();
			renderFullScreenMessage(viewSize, "No data!");
			lastGridSize = Match.empty();
			return new GridUtils.ByteGridSize(1, 1, viewSize);
		}
		
		
		if(ImGui.isKeyPressed(ImGuiKey.R)){
			lastGridSize = Match.empty();
		}
		
		var gridSize = GridUtils.ByteGridSize.compute(viewSize, byteCount, lastGridSize);
		lastGridSize = Match.of(gridSize);
		
		ImGui.begin("Grid debug");
		
		ImGui.checkbox("Show bounding boxes", showBoundingBoxes);
		if(ImGui.checkbox("Merge draw calls", merge)){
			retireScene();
		}
		if(ImGui.button("Invalidate scene")){
			retireScene();
		}
		
		if(ImGui.button("Measure max elements")){
			var buff = new CountingPrimitiveBuffer(renderer.getFontRender());
			try{
				new GridScene(buff, frameData, gridSize).build();
			}catch(Throwable e){
				e.printStackTrace();
			}
			cutoffMax[0] = buff.getCount();
		}
		
		if(ImGui.dragScalar("Element count cutoff", this.cutoff, 0.2F/ImGui.getWindowDpiScale(), -1, cutoffMax[0])){
			retireScene();
		}
		if(ImGui.sliderScalar("#eCOff", this.cutoff, -1, cutoffMax[0])){
			retireScene();
		}
		
		ImGui.end();
		
		buildDispatch:
		if(scene == null){
			synchronized(this){
				if(currentBuildFlag) break buildDispatch;
				currentBuildFlag = true;
			}
			if(SCENE_BUILD_POOL.getQueue().size()>3) UtilL.sleep(20);
			var task = new BuildTask(frameData, gridSize, merge.get(), dynamicTokens.getFontRender(), this.cutoff[0]);
			var lock = this;
			SCENE_BUILD_POOL.execute(() -> {
				if(task.frameData != frameData){
					return;
				}
				var builtScene = task.get();
				synchronized(lock){
					var sceneCurrent = scene != null? scene : oldScene;
					if(sceneCurrent != null && builtScene.createTime.isBefore(sceneCurrent.createTime)){
						return;
					}
					pushScene(builtScene);
					if(task.frameData != frameData){
						retireScene();
					}
				}
			});
		}
		
		if(scene == null && oldScene == null){
			clearStaticRenderer();
			renderFullScreenMessage(viewSize, "Data building...");
			return new GridUtils.ByteGridSize(1, 1, viewSize);
		}
		
		boolean   oldData = false;
		GridScene scene;
		if(this.scene != null){
			scene = this.scene;
			if(!gridSize.equals(scene.gridSize)){
				retireScene();
			}
		}else{
			scene = oldScene;
			oldData = true;
		}
		
		if(shownSceneId != scene.id){
			shownSceneId = scene.id;
			staticRes.reset();
			staticGpuCall = renderer.upload(staticRes, scene.buffer, deviceGC);
		}
		dynamicTokens.renderReady(staticGpuCall);
		
		if(GridUtils.calcByteIndex(scene.gridSize, mouseX(), mouseY(), byteCount, 1) instanceof Some(var p)){
			boolean needsByteOutline = true;
			var     hoverRange       = Range.fromSize(p, 1);
			var     msgs             = scene.messages.collect(p);
			for(int i = msgs.size() - 1; i>=0; i--){
				var message = msgs.get(i);
				if(needsByteOutline && message.range().equals(hoverRange)){
					needsByteOutline = false;
				}
				var off = new Vector2f(i*1F, i*2F);
				switch(message.hoverEffect()){
					case RangeMessageSpace.HoverEffect.None ignore -> { }
					case RangeMessageSpace.HoverEffect.Outline(Col color, float lineWidth) -> {
						outlineByteRange(scene.gridSize, color, message.range(), lineWidth);
					}
					case RangeMessageSpace.HoverEffect.MultiOutline(Col color, float lineWidth, List<Range> ranges) -> {
						for(Range range : ranges){
							var offL = range.isWithin(Range.fromSize(p, 1))? off : new Vector2f();
							outlineByteRange(scene.gridSize, color, range, lineWidth);
						}
					}
				}
			}
			for(var message : msgs){
				messages.add(message.message());
			}
			
			int b = -1;
			try{
				b = frameData.contents().ioMapAt(p, ContentReader::readUnsignedInt1);
			}catch(IOException ignored){ }
			messages.add("Hovered byte at " + p + ": " + (b == -1? "Unable to read byte" : b + "/" + (char)b));
			
			if(needsByteOutline){
				outlineByteRange(scene.gridSize, Col.GREEN.brighter().a(0.6F), hoverRange, 1.5F);
			}
		}
		
		if(showBoundingBoxes.get()){
			dynamicTokens.renderLines(((MergingPrimitiveBuffer)scene.buffer).paths(mouseX(), mouseY()));
		}
		
		if(oldData) dynamicTokens.renderFont(new MsdfFontRender.StringDraw(
			20*ImGui.getWindowDpiScale(),
			Col.DARK_GRAY, "Old data...",
			2, viewSize.height - 2
		));
		
		return scene.gridSize;
	}
	private void outlineByteRange(GridUtils.ByteGridSize gridSize, Col color, Range range, float lineWidth){
		outlineByteRange(gridSize, color, range, lineWidth, new Vector2f());
	}
	private void outlineByteRange(GridUtils.ByteGridSize gridSize, Col color, Range range, float lineWidth, Vector2f offset){
		List<Path.PointsLine> lines = GridUtils.outlineByteRange(color, gridSize, range, lineWidth);
		if(!offset.equals(0, 0)){
			for(Path.PointsLine line : lines){
				for(Vector2f point : line.points()){
					point.add(offset);
				}
			}
		}
		dynamicTokens.renderLines(lines);
	}
	
	private void renderFullScreenMessage(Extent2D viewSize, String str){
		int w         = viewSize.width, h = viewSize.height;
		var fontScale = Math.min(h*0.8F, w/(str.length()*0.8F));
		
		if(GridUtils.stringDrawIn(
			dynamicTokens.getFontRender(), str, new GridRect(w, h), Col.LIGHT_GRAY, fontScale, false
		) instanceof Some(var draw)){
			dynamicTokens.renderFont(draw, draw.withOutline(Col.BLACK.a(0.5F), 1.5F));
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
		staticRes.destroy();
		dynamicRes.destroy();
		renderer.destroy();
		shownSceneId = null;
		dynamicTokens = null;
		staticGpuCall = null;
	}
	
	private void testCurve(){
		var t = (System.currentTimeMillis())/500D;
		
		var controlPoints = Iters.of(3D, 2D, 1D, 4D, 5D).enumerate((i, s) -> new Vector2f(
			(float)Math.sin(t/s)*100 + 200*(i + 1),
			(float)Math.cos(t/s)*100 + 200
		)).toList();
		dynamicTokens.renderLines(List.of(new Path.BezierCurve(controlPoints, 10, new Col(0.1F, 0.3F, 1, 0.6F), 30)));
	}
}
