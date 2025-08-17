package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.PhysicalChunkWalker;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.content.ContentReader;
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
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.IterableLongPP;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MultiRendererBuffer multiRenderer;
	
	
	private final List<String> messages;
	
	private SessionSetView.FrameData frameData;
	
	private Match<GridUtils.ByteGridSize> lastGridSize = Match.empty();
	
	public static final class RenderContext{
		private final Roaring64Bitmap          filled = new Roaring64Bitmap();
		final         SessionSetView.FrameData frameData;
		public        GridUtils.ByteGridSize   gridSize;
		public final  DeviceGC                 deviceGC;
		
		private long sizeCache = -1;
		
		
		private RenderContext(SessionSetView.FrameData frameData, DeviceGC deviceGC){
			this.frameData = frameData;
			this.deviceGC = deviceGC;
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
		
		boolean isNotFilled(long idx){
			return filled.contains(idx);
		}
		boolean isFilled(long idx){
			return filled.contains(idx);
		}
		void fill(long idx){
			filled.addLong(idx);
		}
		void fill(DrawUtils.Range range){
			fill(range.from(), range.to());
		}
		void fill(long start, long end){
			filled.addRange(start, end);
		}
		
	}
	
	public ByteGridComponent(VulkanCore core, ImBoolean open, ImInt sampleEnumIndex, List<String> messages) throws VulkanCodeException{
		super(core, open, sampleEnumIndex);
		
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
		
		var ctx = new RenderContext(frameData, deviceGC);
		
		long byteCount = ctx.getDataSize();
		
		if(byteCount == 0){
			renderNoData(viewSize);
			return;
		}
		
		if(ImGui.isKeyPressed(ImGuiKey.R)){
			lastGridSize = Match.empty();
		}
		
		var res = GridUtils.ByteGridSize.compute(viewSize, byteCount, lastGridSize);
		lastGridSize = Match.of(res);
		
		ctx.gridSize = res;
		
		var byteSize = res.byteSize();
		
		var mousePos = GridUtils.calcByteIndex(res, mouseX(), mouseY(), byteCount, 1);
		
		try{
			multiRenderer.renderBytes(
				deviceGC, frameData.contents().readAll(),
				List.of(
					new ByteGridRender.DrawRange(0, MagicID.size(), Color.BLUE.darker()),
					new ByteGridRender.DrawRange(MagicID.size(), (int)byteCount, Color.GRAY.brighter())
				),
				frameData.writes().mapped(r -> new ByteGridRender.IOEvent(r, ByteGridRender.IOEvent.Type.WRITE))
			);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		
		multiRenderer.renderLines(GridUtils.outlineByteRange(Color.BLUE, res, new Range(0, MagicID.size()), 3));
		
		List<StringDraw> sd = new ArrayList<>();
		
		var hasMagic = ctx.checkMagicID();
		
		annotateMagicID(hasMagic, ctx, res);
		
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
			
			var vsp = DataProvider.newVerySimpleProvider(frameData.contents());
			for(Chunk chunk : vsp.getFirstChunk().chunksAhead()){
				annotateChunk(chunk);
			}
		}catch(IOException ignore){ }
		
		if(mousePos instanceof Some(var p)){
			int b = -1;
			try{
				b = frameData.contents().ioMapAt(p, ContentReader::readUnsignedInt1);
			}catch(IOException ignored){ }
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
	
	private void annotateMagicID(boolean hasMagic, RenderContext ctx, GridUtils.ByteGridSize gridSize) throws IOException, VulkanCodeException{
		var byteSize = ctx.getDataSize();
		if(hasMagic){
			drawByteRanges(ctx, gridSize, List.of(new Range(0, MagicID.size())), Color.BLUE, false, true);
			return;
		}
		
		var bytes = new byte[(int)Math.min(byteSize, MagicID.size())];
		try{
			ctx.frameData.contents().read(0, bytes);
			
			IntPredicate isValidMagicByte = i -> byteSize>i && MagicID.get(i) == bytes[i];
			drawBytes(ctx, gridSize, Iters.range(0, MagicID.size()).filter(isValidMagicByte), Color.BLUE, true, true);
			drawBytes(ctx, gridSize, Iters.range(0, MagicID.size()).filter(isValidMagicByte.negate()), Color.RED, true, true);
		}catch(IOException e){
			drawByteRanges(ctx, gridSize, List.of(new Range(0, MagicID.size())), Color.RED, false, true);
		}
	}
	
	private void drawBytes(RenderContext ctx, GridUtils.ByteGridSize gridSize, IterableIntPP stream, Color color, boolean withChar, boolean force) throws IOException, VulkanCodeException{
		drawByteRanges(ctx, gridSize, DrawUtils.Range.fromInts(stream), color, withChar, force);
	}
	
	private void drawByteRanges(RenderContext ctx, GridUtils.ByteGridSize gridSize, List<Range> ranges, Color color, boolean withChar, boolean force) throws IOException, VulkanCodeException{
		List<DrawUtils.Range> actualRanges;
		if(force) actualRanges = ranges;
		else actualRanges = DrawUtils.Range.filterRanges(ranges, ctx::isNotFilled);
		
		drawByteRangesForced(ctx, gridSize, actualRanges, color, withChar);
	}
	
	private void drawByteRangesForced(RenderContext ctx, GridUtils.ByteGridSize gridSize, List<DrawUtils.Range> ranges, Color color, boolean withChar) throws IOException, VulkanCodeException{
		var col        = ColorUtils.mul(color, 0.8F);
		var bitColor   = col;
		var background = ColorUtils.mul(col, 0.6F);
		
		BiConsumer<IterablePP<Range>, Color> drawIndex = (r, cl) -> {
			fillByteRange(gridSize, r, cl);
		};
		
		List<DrawUtils.Range> clampedOverflow = DrawUtils.Range.clamp(ranges, ctx.getDataSize());
		
		Supplier<IterableLongPP> clampedInts = () -> DrawUtils.Range.toInts(clampedOverflow);
		
		fillByteRange(gridSize, Iters.from(clampedOverflow), background);
		
		fillByteRange(
			gridSize,
			Iters.from(ranges).map(r -> {
				if(r.to()<ctx.getDataSize()) return null;
				if(r.from()<ctx.getDataSize()) return new DrawUtils.Range(ctx.getDataSize(), r.to());
				return r;
			}).nonNulls(),
			ColorUtils.alpha(Color.RED, color.getAlpha()/255F)
		);
		
		for(var range : clampedOverflow){
			var sizeI = Math.toIntExact(range.size());
			var bytes = ctx.frameData.contents().read(range.from(), sizeI);
			
			multiRenderer.renderBytes(ctx.deviceGC, bytes, List.of(new ByteGridRender.DrawRange(0, sizeI, bitColor)), List.of());
		}
		
		if(withChar){
			var c = new Color(1, 1, 1, bitColor.getAlpha()/255F*0.6F);
			
			var fr       = multiRenderer.getFontRender();
			var width    = gridSize.windowSize().width;
			var byteSize = gridSize.byteSize();
			List<StringDraw> chars =
				clampedInts.get().filter(i -> {
					int ub = getUint8(ctx, i);
					return fr.canDisplay((char)ub);
				}).box().flatOptionalsM(i -> {
					int   xi = Math.toIntExact(i%width), yi = Math.toIntExact(i/width);
					float xF = byteSize*xi, yF = byteSize*yi;
					
					return stringDrawIn(Character.toString((char)getUint8(ctx, i)), new GridUtils.Rect(xF, yF, byteSize, byteSize), c, byteSize, false);
				}).toList();
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
		try{
			ub = ctx.frameData.contents().ioMapAt(i, ContentReader::readUnsignedInt1);
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
		return ub;
	}
	
	private void annotateChunk(Chunk chunk){
		annotateStruct(chunk);
	}
	
	private void annotateStruct(Chunk chunk){
	
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
