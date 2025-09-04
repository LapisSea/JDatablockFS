package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.PhysicalChunkWalker;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.tools.ColorUtils;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.tools.DrawUtils.Range;
import com.lapissea.dfs.tools.DrawUtilsVK;
import com.lapissea.dfs.tools.newlogger.SessionSetView;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.IndexBuilder;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VertexBuilder;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender.StringDraw;
import com.lapissea.dfs.tools.newlogger.display.renderers.MultiRendererBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import org.joml.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntPredicate;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MultiRendererBuffer multiRenderer;
	
	
	private final List<String> messages;
	
	private       SessionSetView.FrameData frameData;
	private final SettingsUIComponent      uiSettings;
	
	private Match<GridUtils.ByteGridSize> lastGridSize = Match.empty();
	
	public static final class RenderContext{
		private final BitSet                   filled;
		private final SessionSetView.FrameData frameData;
		public final  GridUtils.ByteGridSize   gridSize;
		public final  DeviceGC                 deviceGC;
		
		private long sizeCache = -1;
		
		
		private RenderContext(SessionSetView.FrameData frameData, GridUtils.ByteGridSize gridSize, DeviceGC deviceGC){
			this.frameData = frameData;
			this.gridSize = gridSize;
			this.deviceGC = deviceGC;
			filled = new BitSet(Math.toIntExact(getDataSize()));
		}
		
		long getDataSize(){
			if(sizeCache != -1) return sizeCache;
			try{
				return sizeCache = (frameData == null? 0 : frameData.contents().getIOSize());
			}catch(IOException e){
				throw new RuntimeException("Failed to get data size", e);
			}
		}
		
		boolean checkMagicID(){
			try{
				frameData.contents().io(MagicID::read);
				return true;
			}catch(IOException ignore){
				return false;
			}
		}
		
		boolean isNotFilled(long idx)   { return !filled.get(Math.toIntExact(idx)); }
		boolean isFilled(long idx)      { return filled.get(Math.toIntExact(idx)); }
		void fill(long idx)             { filled.set(Math.toIntExact(idx)); }
		void fill(DrawUtils.Range range){ fill(range.from(), range.to()); }
		void fill(long start, long end){
			for(long i = start; i<end; i++){
				filled.set(Math.toIntExact(i));
			}
		}
		
	}
	
	public ByteGridComponent(VulkanCore core, ImBoolean open, SettingsUIComponent uiSettings, List<String> messages) throws VulkanCodeException{
		super(core, open, uiSettings.byteGridSampleEnumIndex);
		this.uiSettings = uiSettings;
		this.messages = messages;
		
		clearColor.set(0.4, 0.4, 0.4, 1);
		
		multiRenderer = new MultiRendererBuffer(core);
	}
	
	public void setDisplayData(SessionSetView.FrameData src) throws IOException{
		frameData = src;
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
		try{
			recordBackbuffer(deviceGC, viewSize);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		multiRenderer.submit(deviceGC, lastGridSize.orElse(new GridUtils.ByteGridSize(1, 1, viewSize)), cmdBuffer);
	}
	protected void recordBackbuffer(DeviceGC deviceGC, Extent2D viewSize) throws VulkanCodeException, IOException{
		
		{
			boolean errorMode = false;//TODO should error mode even be used? Bake whole database once instead of at render time?
			var     color     = errorMode? Color.RED.darker() : new Color(0xDBFFD700, true);
			
			multiRenderer.renderMesh(GridUtils.backgroundDots(viewSize, color, ImGui.getWindowDpiScale()));
		}
		
		long byteCount = getFrameSize(frameData);
		
		if(byteCount == 0){
			renderNoData(viewSize);
			return;
		}
		
		if(ImGui.isKeyPressed(ImGuiKey.R)){
			lastGridSize = Match.empty();
		}
		
		var gridSize = GridUtils.ByteGridSize.compute(viewSize, byteCount, lastGridSize);
		lastGridSize = Match.of(gridSize);
		
		var ctx = new RenderContext(frameData, gridSize, deviceGC);
		
		var mouseHoverIndex = GridUtils.calcByteIndex(gridSize, mouseX(), mouseY(), byteCount, 1);
		
		var hasMagic = ctx.checkMagicID();
		
		annotateMagicID(ctx, hasMagic);
		try{
			frameData.contents().io(MagicID::read);
			
			var byteBae = new StringDraw(
				gridSize.byteSize(), new Color(0.1F, 0.3F, 1, 1), StandardCharsets.UTF_8.decode(MagicID.get()).toString(), 0, 0
			);
			
			if(stringDrawIn(byteBae, new GridUtils.Rect(0, 0, MagicID.size(), 1).scale(gridSize.byteSize()), false) instanceof Some(var str)){
				multiRenderer.renderFont(str, str.withOutline(Color.black, 1F));
			}
			
			if(GridUtils.isRangeHovered(mouseHoverIndex, 0, MagicID.size())){
				messages.add("The magic ID that identifies this as a DFS database: " + StandardCharsets.UTF_8.decode(MagicID.get()));
				
				outlineByteRange(gridSize, Color.BLUE, new Range(0, MagicID.size()), 3);
			}
			
			var vsp = DataProvider.newVerySimpleProvider(frameData.contents());
			for(Chunk chunk : vsp.getFirstChunk().chunksAhead()){
				annotateChunk(ctx, chunk);
			}
		}catch(IOException ignore){
			messages.add("Does not have valid magic ID");
		}
		
		
		try{
			drawByteRanges(ctx, List.of(new Range(0, frameData.contents().getIOSize())), Color.LIGHT_GRAY, true, false);
			multiRenderer.renderBytes(ctx.deviceGC, 0, null, List.of(), Iters.from(frameData.writes()).map(r -> new ByteGridRender.IOEvent((int)r.start, (int)r.end(), ByteGridRender.IOEvent.Type.WRITE)));
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		
		if(mouseHoverIndex instanceof Some(var p)){
			int b = -1;
			try{
				b = frameData.contents().ioMapAt(p, ContentReader::readUnsignedInt1);
			}catch(IOException ignored){ }
			messages.add("Hovered byte at " + p + ": " + (b == -1? "Unable to read byte" : b + "/" + (char)b));
			
			if(findHoverChunk(frameData.contents(), p) instanceof Some(var chunk)){
				var chRange = new Range(chunk.getPtr().getValue(), chunk.dataEnd());
				outlineByteRange(gridSize, Color.CYAN.darker(), chRange, 2);
				messages.add("Hovered chunk: " + chunk);
			}
			multiRenderer.renderLines(
				GridUtils.outlineByteRange(Color.WHITE, gridSize, new Range(p, p + 1), 1.5F)
			);
		}
	}
	private void outlineByteRange(GridUtils.ByteGridSize gridSize, Color color, Range range, float lineWidth){
		var lines = GridUtils.outlineByteRange(color, gridSize, range, lineWidth);
		multiRenderer.renderLines(lines);
	}
	
	private void annotateMagicID(RenderContext ctx, boolean hasMagic) throws IOException, VulkanCodeException{
		var byteSize = ctx.getDataSize();
		if(hasMagic){
			drawByteRanges(ctx, List.of(new Range(0, MagicID.size())), Color.BLUE, false, true);
			return;
		}
		
		var bytes = new byte[(int)Math.min(byteSize, MagicID.size())];
		try{
			ctx.frameData.contents().read(0, bytes);
			
			IntPredicate isValidMagicByte = i -> byteSize>i && MagicID.get(i) == bytes[i];
			drawBytes(ctx, Iters.range(0, MagicID.size()).filter(isValidMagicByte), Color.BLUE, true, true);
			drawBytes(ctx, Iters.range(0, MagicID.size()).filter(isValidMagicByte.negate()), Color.RED, true, true);
		}catch(IOException e){
			drawByteRanges(ctx, List.of(new Range(0, MagicID.size())), Color.RED, false, true);
		}
	}
	
	private void drawBytes(RenderContext ctx, IterableIntPP stream, Color color, boolean withChar, boolean force) throws IOException, VulkanCodeException{
		drawByteRanges(ctx, DrawUtils.Range.fromInts(stream), color, withChar, force);
	}
	
	private void drawByteRanges(RenderContext ctx, List<Range> ranges, Color color, boolean withChar, boolean force) throws IOException, VulkanCodeException{
		List<DrawUtils.Range> actualRanges;
		if(force) actualRanges = ranges;
		else actualRanges = DrawUtils.Range.filterRanges(ranges, ctx::isNotFilled);
		
		drawByteRangesForced(ctx, actualRanges, color, withChar);
	}
	
	private void drawByteRangesForced(RenderContext ctx, List<DrawUtils.Range> ranges, Color color, boolean withChar) throws IOException, VulkanCodeException{
		GridUtils.ByteGridSize gridSize = ctx.gridSize;
		
		var col        = ColorUtils.mul(color, 0.8F);
		var background = ColorUtils.mul(col, 0.6F);
		
		List<DrawUtils.Range> clampedOverflow = DrawUtils.Range.clamp(ranges, ctx.getDataSize());
		
		fillByteRange(gridSize, Iters.from(clampedOverflow), background);
		
		fillByteRange(
			gridSize,
			Iters.from(ranges).filter(r -> r.to()>=ctx.getDataSize()).map(r -> {
				if(r.from()<ctx.getDataSize()) return new DrawUtils.Range(ctx.getDataSize(), r.to());
				return r;
			}),
			ColorUtils.alpha(Color.RED, color.getAlpha()/255F)
		);
		
		for(var range : clampedOverflow){
			var from  = Math.toIntExact(range.from());
			var to    = Math.toIntExact(range.to());
			var sizeI = to - from;
			var bytes = ctx.frameData.contents().read(range.from(), sizeI);
			
			multiRenderer.renderBytes(ctx.deviceGC, range.from(), bytes, List.of(new ByteGridRender.DrawRange(from, to, col)), List.of());
		}
		
		if(withChar){
			var c = new Color(1, 1, 1, col.getAlpha()/255F*0.6F);
			
			var fr       = multiRenderer.getFontRender();
			var width    = gridSize.bytesPerRow();
			var byteSize = gridSize.byteSize();
			
			List<StringDraw> chars = new ArrayList<>();
			
			var it = DrawUtils.Range.toInts(clampedOverflow).iterator();
			
			while(it.hasNext()){
				var  i  = it.nextLong();
				char ub = (char)getUint8(ctx, i);
				
				if(!fr.canDisplay(ub)){
					continue;
				}
				
				int   xi = Math.toIntExact(i%width), yi = Math.toIntExact(i/width);
				float xF = byteSize*xi, yF = byteSize*yi;
				
				if(stringDrawIn(Character.toString(ub), new GridUtils.Rect(xF, yF, byteSize, byteSize), c, byteSize, false) instanceof Some(var sd)){
					chars.add(sd);
				}
			}
			
			multiRenderer.renderFont(chars);
		}
		for(var range : clampedOverflow){
			ctx.fill(range);
		}
	}
	
	private void fillByteRange(GridUtils.ByteGridSize gridSize, IterablePP<Range> ranges, Color color){
		var count = IterablePP.SizedPP.tryGet(ranges).orElse(4);
		var mesh  = new Geometry.IndexedMesh(new VertexBuilder(1 + 4*2*count), new IndexBuilder(1 + 6*2*count));
		for(var range : ranges){
			DrawUtilsVK.fillByteRange(gridSize, mesh, color, range);
		}
		multiRenderer.renderMesh(mesh);
	}
	private static int getUint8(RenderContext ctx, long i){
		int ub;
		var contents = ctx.frameData.contents();
		try(var io = contents.ioAt(i)){
			ub = io.readUnsignedInt1();
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
		return ub;
	}
	
	private void annotateChunk(RenderContext ctx, Chunk chunk) throws VulkanCodeException, IOException{
		annotateStruct(ctx, chunk, Chunk.PIPE, chunk.getPtr().getValue());
	}
	
	private <T extends IOInstance<T>> void annotateStruct(RenderContext ctx, T instance, StructPipe<T> pipe, long offset) throws VulkanCodeException, IOException{
		
		var size = pipe.calcUnknownSize(null, instance, WordSpace.BYTE);
		drawByteRanges(ctx, List.of(new Range(offset, offset + size)), Color.cyan, false, true);
		
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
