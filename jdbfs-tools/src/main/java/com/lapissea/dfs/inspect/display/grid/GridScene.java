package com.lapissea.dfs.inspect.display.grid;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.inspect.SessionSetView;
import com.lapissea.dfs.inspect.display.primitives.Geometry;
import com.lapissea.dfs.inspect.display.primitives.IndexBuilder;
import com.lapissea.dfs.inspect.display.primitives.Path;
import com.lapissea.dfs.inspect.display.primitives.VertexBuilder;
import com.lapissea.dfs.inspect.display.renderers.ByteGridRender;
import com.lapissea.dfs.inspect.display.renderers.MsdfFontRender;
import com.lapissea.dfs.inspect.display.renderers.PrimitiveBuffer;
import com.lapissea.dfs.inspect.display.vk.DrawUtilsVK;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.tools.BinaryGridRenderer.Pointer;
import com.lapissea.dfs.tools.ColorUtils;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.tools.utils.NanoClock;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import org.joml.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntPredicate;

public class GridScene{
	public final RangeMessageSpace        messages = new RangeMessageSpace();
	public final PrimitiveBuffer          buffer;
	public final SessionSetView.FrameData frameData;
	public final GridUtils.ByteGridSize   gridSize;
	
	private final long   dataSize;
	private final BitSet filled;
	
	private DataProvider dataProvider;
	
	public final Instant createTime = NanoClock.now();
	public final Object  id         = new Object();
	
	public GridScene(PrimitiveBuffer buffer, SessionSetView.FrameData frameData, GridUtils.ByteGridSize gridSize){
		this.buffer = buffer;
		this.frameData = frameData;
		this.gridSize = gridSize;
		
		try{
			dataSize = frameData.contents().getIOSize();
		}catch(IOException e){
			throw new RuntimeException("Failed to get data size", e);
		}
		filled = new BitSet(Math.toIntExact(dataSize));
	}
	
	private class FaultTolerantChunkWalker{
		
		long pointer    = MagicID.size();
		long errorStart = -1, errorEnd = -1;
		IOException error;
		
		private void run() throws IOException{
			while(pointer<=dataSize){
				try{
					var chunk = dataProvider.getChunk(ChunkPointer.of(pointer));
					flushError();
					
					annotateStruct(Chunk.PIPE, ChunkPointer.NULL, chunk.getPtr().getValue());
					
					var chRange = new DrawUtils.Range(chunk.getPtr().getValue(), chunk.dataEnd());
					messages.add("Hovered chunk: " + chunk, chRange, new RangeMessageSpace.HoverEffect.Outline(Color.CYAN.darker(), 2));
					
					if(chunk.checkLastPhysical()){
						break;
					}
					pointer = chunk.dataEnd();
				}catch(IOException e){
					if(error == null){
						error = e;
						errorStart = errorEnd = pointer;
					}
					if(errorEnd + 1 == pointer) errorEnd++;
					pointer++;
				}
			}
			flushError();
		}
		
		private void flushError() throws IOException{
			if(errorStart == -1) return;
			var range = new DrawUtils.Range(errorStart, errorEnd);
			drawByteRangesForced(List.of(range), Color.RED.darker(), true);
			messages.add(Utils.errToStackTrace(error), range, new RangeMessageSpace.HoverEffect.Outline(Color.ORANGE, 3));
			errorStart = errorEnd = -1;
			error = null;
		}
		
	}
	
	public void build() throws IOException{
		startDataProvider();
		
		new FaultTolerantChunkWalker().run();
		
		if(dataProvider instanceof Cluster cluster){
			
			var rootWalk = cluster.rootWalker(null, false);
			var root     = rootWalk.getRoot();
			var rootPipe = FixedStructPipe.of(root.getClass());
			annotateStruct(rootPipe, cluster.getFirstChunk().getPtr(), 0);
		}
		
		drawByteRanges(List.of(new DrawUtils.Range(0, frameData.contents().getIOSize())), Color.LIGHT_GRAY, true, false);
		buffer.renderBytes(
			0, null,
			List.of(),
			Iters.from(frameData.writes()).map(r -> new ByteGridRender.IOEvent((int)r.start, (int)r.end(), ByteGridRender.IOEvent.Type.WRITE))
		);
	}
	
	private void startDataProvider() throws IOException{
		try{
			frameData.contents().io(MagicID::read);
			dataProvider = new Cluster(frameData.contents());
			drawByteRanges(List.of(new DrawUtils.Range(0, MagicID.size())), Color.BLUE, false, true);
			
			var byteBae = new MsdfFontRender.StringDraw(
				gridSize.byteSize(), new Color(0.1F, 0.3F, 1, 1), StandardCharsets.UTF_8.decode(MagicID.get()).toString(), 0, 0
			);
			if(stringDrawIn(byteBae, gridSize.findBestRectScaled(DrawUtils.Range.fromSize(0, MagicID.size())), false) instanceof Match.Some(var str)){
				buffer.renderFont(str, str.withOutline(Color.black, 1F));
			}
			
			messages.add(
				"The magic ID that identifies this as a DFS database: " + StandardCharsets.UTF_8.decode(MagicID.get()),
				new DrawUtils.Range(0, MagicID.size()),
				new RangeMessageSpace.HoverEffect.Outline(Color.BLUE, 3)
			);
			return;
		}catch(IOException ignore){
			messages.add("Does not have valid magic ID", new DrawUtils.Range(0, dataSize));
		}
		
		dataProvider = DataProvider.newVerySimpleProvider(frameData.contents());
		
		var bytes = new byte[(int)Math.min(dataSize, MagicID.size())];
		try{
			frameData.contents().read(0, bytes);
			
			IntPredicate isValidMagicByte = i -> dataSize>i && MagicID.get(i) == bytes[i];
			drawBytes(Iters.range(0, MagicID.size()).filter(isValidMagicByte), Color.BLUE, true, true);
			drawBytes(Iters.range(0, MagicID.size()).filter(isValidMagicByte.negate()), Color.RED, true, true);
		}catch(IOException e){
			drawByteRanges(List.of(new DrawUtils.Range(0, MagicID.size())), Color.RED, false, true);
		}
	}
	
	private <T extends IOInstance<T>> void annotateStruct(StructPipe<T> pipe, ChunkPointer ptr, long offset) throws IOException{
		
		if(pipe.getType().needsBuilderObj()){
			annotateStruct(pipe.getBuilderPipe(), ptr, offset);
			return;
		}
		
		var info = FieldReader.readFields(dataProvider, pipe, ptr, offset);
		for(var rf : info.fields()){
			var rand = new RawRandom(rf.field().toString().hashCode());
			var col  = new Color(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256));
			annotateByteField(info.value(), rf, col);
		}
		
	}
	
	private <T extends IOInstance<T>, V> void annotateByteField(T instance, FieldReader.Res<T, V> rf, Color col) throws IOException{
		if(rf.range().size() == 0) return;
		
		ChunkPointer  block = rf.block();
		IOField<T, V> field = rf.field();
		
		DrawUtils.Range bestRange  = new DrawUtils.Range(0, 0);
		DrawUtils.Range firstRange = null, lastRange = null;
		
		var logicalRange = rf.range();
		var ranges = block.isNull()?
		             List.of(logicalRange) :
		             DrawUtils.chainRangeResolve(dataProvider, new Reference(block, 0), logicalRange.from(), logicalRange.size()).toModList();
		
		var message = field + " " + rf.value();
		
		GridRect rect = null;
		
		boolean drawDetails = gridSize.bytesPerRow()>6;
		
		for(DrawUtils.Range rng : ranges){
			messages.add(message, rng, new RangeMessageSpace.HoverEffect.Outline(col.brighter(), 2));
			if(bestRange.size()<gridSize.bytesPerRow()){
				var contiguousRange = DrawUtils.findBestContiguousRange(gridSize.bytesPerRow(), rng);
				if(bestRange.size()<contiguousRange.size()) bestRange = contiguousRange;
			}
			if(firstRange == null) firstRange = rng;
			if(lastRange != null) recordPointer(lastRange.to() - 1, rng.from(), 0, col, field.toString(), 0.2F);
			lastRange = rng;
			drawByteRanges(List.of(rng), ColorUtils.alpha(ColorUtils.mul(col, 0.8F), 0.6F), false, true);
			
			if(drawDetails){
				var nr = gridSize.findBestRectScaled(rng);
				if(rect == null || rect.area()<nr.area()){
					rect = nr;
				}
			}
		}
		
		if(!ranges.isEmpty()){
			if(field instanceof RefField<T, V> refF){
				var ref       = refF.getReference(instance);
				var globalOff = ref.calcGlobalOffset(dataProvider);
				recordPointer(firstRange.from(), globalOff, (int)firstRange.size(), Color.RED, message, 3);
			}else if(field.getType() == Reference.class){
				var ref       = (Reference)rf.value();
				var globalOff = ref.calcGlobalOffset(dataProvider);
				recordPointer(firstRange.from(), globalOff, (int)firstRange.size(), Color.RED, message, 3);
			}
		}
		
		if(!drawDetails) return;
		
		
		if(stringDrawIn(message, rect, col, gridSize.byteSize()*0.8F, false) instanceof Match.Some(var draw)){
			buffer.renderFont(draw, draw.withOutline(new Color(0, 0, 0, 0.5F), 1.5F));
		}
		
	}
	
	private void recordPointer(long from, long to, int size, Color color, String message, float widthFactor){
		recordPointer(new Pointer(from, to, size, color, message, widthFactor));
	}
	
	private void recordPointer(Pointer ptr){
		
		var start = ptr.from();
		var end   = ptr.to();
		var width = gridSize.bytesPerRow();
		int pSiz  = ptr.size();
		
		long
			xPosFrom = start%width,
			yPosFrom = start/width,
			xPosTo = end%width,
			yPosTo = end/width;
		
		var direction = start<end;
		var ySmall    = Math.abs(yPosFrom - yPosTo)>1;
		var dirY      = direction? 1 : -1;
		var dirX      = 0;
		
		float offScale = ySmall? Math.abs(yPosFrom - yPosTo)/6F : 2;
		
		var fromOff = new Vector2f(dirX, dirY).mul(offScale);
		var toOff   = new Vector2f(-dirX, (ySmall? -1 : 1)*dirY).mul(offScale);
		
		var fromOrg = new Vector2f((xPosFrom + (pSiz == 0? 0 : 0.5F)), yPosFrom + 0.5F);
		var toOrg   = new Vector2f(xPosTo + 0.5F, yPosTo + 0.5F);
		
		var from = new Vector2f(fromOrg).add(fromOff);
		var to   = new Vector2f(toOrg).add(toOff);


//		var mix       = 12;
//		var fromMixed = from.mul(mix, new Vector2f()).add(to).div(mix + 1);
//		var toMixed   = to.mul(mix, new Vector2f()).add(from).div(mix + 1);


//		var screenHeight = gridSize.windowSize().height;
//		var screenWidth  = gridSize.windowSize().width;
//
//		if(xFrom<0 || xFrom>screenWidth) xFrom = xFromOrg - xFromOff;
//		if(yFrom<0 || yFrom>screenHeight) yFrom = yFromOrg - yFromOff;
//		if(xTo<0 || xTo>screenWidth) xTo = xToOrg - xToOff;
//		if(yTo<0 || yTo>screenHeight) yTo = yToOrg - yToOff;
		
		
		var s = gridSize.byteSize();
		var points = List.of(
			fromOrg.mul(s),
			from.mul(s),
			to.mul(s),
			toOrg.mul(s)
		);
		
		
		buffer.renderLine(new Path.BezierCurve(
			points,
			ptr.widthFactor(),
			ptr.color(), 30
		));

//		buffer.renderLines(Iters.from(points).toList(e -> new Geometry.PointsLine(List.of(
//			e, new Vector2f(e).add(0, 10)
//		), 5, Color.green, false)));
		
	}
	
	private void drawBytes(IterableIntPP stream, Color color, boolean withChar, boolean force) throws IOException{
		drawByteRanges(DrawUtils.Range.fromInts(stream), color, withChar, force);
	}
	
	private void drawByteRanges(List<DrawUtils.Range> ranges, Color color, boolean withChar, boolean force) throws IOException{
		List<DrawUtils.Range> actualRanges;
		if(force) actualRanges = ranges;
		else actualRanges = DrawUtils.Range.filterRanges(ranges, this::isNotFilled);
		
		drawByteRangesForced(actualRanges, color, withChar);
	}
	
	private void drawByteRangesForced(List<DrawUtils.Range> ranges, Color color, boolean withChar) throws IOException{
		var col        = ColorUtils.mul(color, 0.8F);
		var background = ColorUtils.mul(col, 0.6F);
		
		List<DrawUtils.Range> clampedOverflow = DrawUtils.Range.clamp(ranges, dataSize);
		
		fillByteRange(Iters.from(clampedOverflow), background);
		
		fillByteRange(
			Iters.from(ranges).filter(r -> r.to()>=dataSize).map(r -> {
				if(r.from()<dataSize) return new DrawUtils.Range(dataSize, r.to());
				return r;
			}),
			ColorUtils.alpha(Color.RED, color.getAlpha()/255F)
		);
		
		for(var range : clampedOverflow){
			var from  = Math.toIntExact(range.from());
			var to    = Math.toIntExact(range.to());
			var sizeI = to - from;
			var bytes = frameData.contents().read(range.from(), sizeI);
			
			buffer.renderBytes(range.from(), bytes, List.of(new ByteGridRender.DrawRange(from, to, col)), java.util.List.of());
		}
		
		if(withChar){
			var c = new Color(1, 1, 1, col.getAlpha()/255F*0.6F);
			
			var fr       = buffer.getFontRender();
			var width    = gridSize.bytesPerRow();
			var byteSize = gridSize.byteSize();
			
			List<MsdfFontRender.StringDraw> chars = new ArrayList<>();
			
			var it = DrawUtils.Range.toInts(clampedOverflow).iterator();
			
			while(it.hasNext()){
				var  i  = it.nextLong();
				char ub = (char)getUint8(frameData, i);
				
				if(!fr.canDisplay(ub)){
					continue;
				}
				
				int   xi = Math.toIntExact(i%width), yi = Math.toIntExact(i/width);
				float xF = byteSize*xi, yF = byteSize*yi;
				
				if(stringDrawIn(Character.toString(ub), new GridRect(xF, yF, byteSize, byteSize), c, byteSize, false) instanceof Match.Some(
					var sd
				)){
					chars.add(sd);
				}
			}
			
			buffer.renderFont(chars);
		}
		for(var range : clampedOverflow){
			fill(range);
		}
	}
	
	private void fillByteRange(IterablePP<DrawUtils.Range> ranges, Color color){
		var count = IterablePP.SizedPP.tryGet(ranges).orElse(4);
		var mesh  = new Geometry.IndexedMesh(new VertexBuilder(1 + 4*2*count), new IndexBuilder(1 + 6*2*count));
		for(var range : ranges){
			DrawUtilsVK.fillByteRange(gridSize, mesh, color, range);
		}
		buffer.renderMesh(mesh);
	}
	
	private Match<MsdfFontRender.StringDraw> stringDrawIn(MsdfFontRender.StringDraw draw, GridRect area, boolean alignLeft){
		return stringDrawIn(draw.string(), area, draw.color(), draw.pixelHeight(), alignLeft);
	}
	private Match<MsdfFontRender.StringDraw> stringDrawIn(String s, GridRect area, Color color, float fontScale, boolean alignLeft){
		return GridUtils.stringDrawIn(buffer.getFontRender(), s, area, color, fontScale, alignLeft);
	}
	private void outlineByteRange(GridUtils.ByteGridSize gridSize, Color color, DrawUtils.Range range, float lineWidth){
		var lines = GridUtils.outlineByteRange(color, gridSize, range, lineWidth);
		buffer.renderLines(lines);
	}
	
	private static int getUint8(SessionSetView.FrameData frameData, long i){
		int ub;
		var contents = frameData.contents();
		try(var io = contents.ioAt(i)){
			ub = io.readUnsignedInt1();
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
		return ub;
	}
	
	private boolean isNotFilled(long idx)   { return !filled.get(Math.toIntExact(idx)); }
	private boolean isFilled(long idx)      { return filled.get(Math.toIntExact(idx)); }
	private void fill(long idx)             { filled.set(Math.toIntExact(idx)); }
	private void fill(DrawUtils.Range range){ fill(range.from(), range.to()); }
	private void fill(long start, long end){
		for(long i = start; i<end; i++){
			filled.set(Math.toIntExact(i));
		}
	}
	
}
