package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.PhysicalChunkWalker;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.tools.DrawUtils.Range;
import com.lapissea.dfs.tools.newlogger.SessionSetView;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender.StringDraw;
import com.lapissea.dfs.tools.newlogger.display.renderers.MultiRendererBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import imgui.type.ImInt;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MultiRendererBuffer multiRenderer;
	
	private record DisplayData(SessionSetView.FrameData frameData, long size){ }
	
	private final List<String> messages;
	
	private DisplayData displayData;
	
	private Match<GridUtils.ByteGridSize> lastGridSize = Match.empty();
	
	public ByteGridComponent(VulkanCore core, ImBoolean open, ImInt sampleEnumIndex, List<String> messages) throws VulkanCodeException{
		super(core, open, sampleEnumIndex);
		
		this.messages = messages;
		
		clearColor.set(0.4, 0.4, 0.4, 1);
		
		multiRenderer = new MultiRendererBuffer(core);
	}
	
	public void setDisplayData(SessionSetView.FrameData src) throws IOException{
		displayData = new DisplayData(src, src.contents().getIOSize());
	}
	
	@Override
	protected boolean needsRerender(){ return true; }
	
	@Override
	protected void renderBackbuffer(DeviceGC deviceGC, CommandBuffer cmdBuffer, Extent2D viewSize) throws VulkanCodeException{
		multiRenderer.reset();
		recordBackbuffer(deviceGC, viewSize);
		multiRenderer.submit(deviceGC, lastGridSize.orElse(new GridUtils.ByteGridSize(1, 1, viewSize)), cmdBuffer);
	}
	protected void recordBackbuffer(DeviceGC deviceGC, Extent2D viewSize) throws VulkanCodeException{
		
		{
			boolean errorMode = false;//TODO should error mode even be used? Bake whole database once instead of at render time?
			var     color     = errorMode? Color.RED.darker() : new Color(0xDBFFD700, true);
			
			multiRenderer.renderLines(GridUtils.backgroundDots(viewSize, color));
		}
		
		if(displayData == null || displayData.size == 0){
			renderNoData(viewSize);
			return;
		}
		
		
		var frameData = displayData.frameData;
		
		long byteCount = displayData.size;
		
		if(ImGui.isKeyPressed(ImGuiKey.R)){
			lastGridSize = Match.empty();
		}
		
		var res = GridUtils.ByteGridSize.compute(viewSize, byteCount, lastGridSize);
		lastGridSize = Match.of(res);
		
		var byteSize = res.byteSize();
		
		var mousePos = GridUtils.calcByteIndex(viewSize, res, mouseX(), mouseY(), byteCount, 1);
		
		try{
			multiRenderer.renderBytes(
				deviceGC, frameData.contents().readAll(),
				List.of(
					new ByteGridRender.DrawRange(0, MagicID.size(), Color.BLUE.darker()),
					new ByteGridRender.DrawRange(MagicID.size(), (int)displayData.size, Color.GRAY.brighter())
				),
				frameData.writes().mapped(r -> new ByteGridRender.IOEvent(r, ByteGridRender.IOEvent.Type.WRITE))
			);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		
		multiRenderer.renderLines(GridUtils.outlineByteRange(Color.BLUE, res, new Range(0, MagicID.size()), 3));
		
		List<StringDraw> sd = new ArrayList<>();
		
		try{
			frameData.contents().io(MagicID::read);
			
			var byteBae = new StringDraw(
				byteSize, new Color(0.1F, 0.3F, 1, 1), StandardCharsets.UTF_8.decode(MagicID.get()).toString(), 0, 0
			);
			
			if(stringDrawIn(byteBae, new GridUtils.Rect(0, 0, MagicID.size(), 1).scale(byteSize), false) instanceof Some(var str)){
				sd.add(str);
				sd.add(str.withOutline(Color.black, 1F));
			}
			
			if(GridUtils.isRangeHovered(mousePos, 0, MagicID.size())){
				messages.add("The magic ID that identifies this as a DFS database: " + StandardCharsets.UTF_8.decode(MagicID.get()));
			}
		}catch(IOException ignore){ }
		
		if(mousePos instanceof Some(var p)){
			int b = -1;
			try{
				b = frameData.contents().ioMapAt(p, ContentReader::readUnsignedInt1);
			}catch(IOException e){ }
			messages.add("Hovered byte at " + p + ": " + (b == -1? "Unable to read byte" : b + "/" + (char)b));
			
			if(findHoverChunk(frameData.contents(), p) instanceof Some(var chunk)){
				var chRange = new Range(chunk.getPtr().getValue(), chunk.dataEnd());
				multiRenderer.renderLines(
					GridUtils.outlineByteRange(Color.CYAN.darker(), res, chRange, 2)
				);
				messages.add("Hovered chunk: " + chunk);
			}
			multiRenderer.renderLines(
				GridUtils.outlineByteRange(Color.WHITE, res, new Range(p, p + 1), 1.5F)
			);
		}
		
		multiRenderer.renderFont(sd);
	}
	
	private Match<Chunk> findHoverChunk(IOInterface data, long hoverPos){
		try{
			var prov = DataProvider.newVerySimpleProvider(data);
			for(Chunk chunk : new PhysicalChunkWalker(prov.getFirstChunk())){
				if(chunk.rangeIntersects(hoverPos)){
					return Match.of(chunk);
				}
			}
		}catch(IOException ignore){ }
		return Match.empty();
	}
	
	private void renderNoData(Extent2D viewSize){
		var str = "No data!";
		
		int w         = viewSize.width, h = viewSize.height;
		var fontScale = Math.min(h*0.8F, w/(str.length()*0.8F));
		
		if(stringDrawIn(str, new GridUtils.Rect(w, h), Color.LIGHT_GRAY, fontScale, false) instanceof Some(var draw)){
			multiRenderer.renderFont(draw, draw.withOutline(new Color(0, 0, 0, 0.5F), 1.5F));
		}
	}
	
	private Match<StringDraw> stringDrawIn(StringDraw draw, GridUtils.Rect area, boolean alignLeft){
		return stringDrawIn(draw.string(), area, draw.color(), draw.pixelHeight(), alignLeft);
	}
	private Match<StringDraw> stringDrawIn(String s, GridUtils.Rect area, Color color, float fontScale, boolean alignLeft){
		return GridUtils.stringDrawIn(multiRenderer.getFontRender(), s, area, color, fontScale, alignLeft);
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		super.unload(tScope);
		multiRenderer.destroy();
	}
	
}
