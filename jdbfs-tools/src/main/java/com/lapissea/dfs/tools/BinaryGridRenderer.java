package com.lapissea.dfs.tools;


import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigUtils;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.ChainWalker;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.core.chunk.ChunkSet;
import com.lapissea.dfs.core.chunk.PhysicalChunkWalker;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.CollectionInfo;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.tools.logging.MemFrame;
import com.lapissea.dfs.tools.render.RenderBackend;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.BasicFieldAccessor;
import com.lapissea.dfs.type.field.access.TypeFlag;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.field.fields.CollectionAdapter;
import com.lapissea.dfs.type.field.fields.NoIOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldInlineObject;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.dfs.type.field.fields.reflection.wrappers.IOFieldFusedString;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NanoTimer;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.StreamUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import org.joml.SimplexNoise;

import java.awt.Color;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.ConfigDefs.CONFIG_PROPERTY_PREFIX;
import static com.lapissea.dfs.logging.Log.trace;
import static com.lapissea.dfs.logging.Log.warn;
import static com.lapissea.dfs.type.field.StoragePool.IO;
import static com.lapissea.util.UtilL.async;
import static org.lwjgl.glfw.GLFW.*;

@SuppressWarnings({"UnnecessaryLocalVariable", "SameParameterValue", "rawtypes", "unchecked"})
public class BinaryGridRenderer implements DataRenderer{
	
	static{
		Thread.startVirtualThread(() -> {
			try{
				Cluster.emptyMem().rootWalker(MemoryWalker.PointerRecord.NOOP, false).walk();
			}catch(IOException ignored){ }
		});
	}
	
	enum ErrorLogLevel{
		NONE,
		NAME,
		STACK,
		NAMED_STACK
	}
	
	record RenderContext(
		RenderBackend renderer,
		byte[] bytes, BitSet filled,
		int width, float pixelsPerByte, int hoverByteX, int hoverByteY, int hoverByteIndex,
		List<HoverMessage> hoverMessages){
		
		private static int calcIndex(byte[] bytes, RenderBackend.DisplayInterface dis, float pixelsPerByte, float zoom){
			int width     = (int)Math.max(1, dis.getWidth()/pixelsPerByte);
			int xByte     = calcX(dis, pixelsPerByte, zoom);
			int yByte     = calcY(dis, pixelsPerByte, zoom);
			int byteIndex = yByte*width + xByte;
			if(xByte>=width || byteIndex>=bytes.length) return -1;
			else return byteIndex;
		}
		
		RenderContext(RenderBackend renderer,
		              byte[] bytes,
		              float pixelsPerByte, float zoom, RenderBackend.DisplayInterface dis,
		              List<HoverMessage> hoverMessages){
			this(renderer, bytes, new BitSet(),
			     (int)Math.max(1, dis.getWidth()/pixelsPerByte), pixelsPerByte*zoom,
			     calcX(dis, pixelsPerByte, zoom),
			     calcY(dis, pixelsPerByte, zoom),
			     calcIndex(bytes, dis, pixelsPerByte, zoom),
			     hoverMessages);
		}
		
		private static int calcY(RenderBackend.DisplayInterface d, float pixelsPerByte, float zoom){
			var h    = d.getHeight();
			var offY = (h - h*zoom)*(d.getMouseY()/(float)h);
			return (int)((d.getMouseY() - offY)/(pixelsPerByte*zoom));
		}
		private static int calcX(RenderBackend.DisplayInterface d, float pixelsPerByte, float zoom){
			var w    = d.getWidth();
			var offX = (w - w*zoom)*(d.getMouseX()/(float)w);
			return (int)((d.getMouseX() - offX)/(pixelsPerByte*zoom));
		}
		
		boolean isRangeHovered(DrawUtils.Range range){
			var f = range.from();
			var t = range.to();
			return f<=hoverByteIndex && hoverByteIndex<t;
		}
		
	}
	
	private record Pointer(long from, long to, int size, Color color, String message, float widthFactor){ }
	
	private boolean errorMode;
	private long    renderCount;
	
	//	private       RenderBackend          renderer;
	private final RenderBackend          direct;
	private final RenderBackend.Buffered buff;
	
	private final NanoTimer.Avg frameTimer = new NanoTimer.Avg();
	
	private Optional<SessionHost.HostedSession> displayedSession = Optional.empty();
	
	private float              pixelsPerByte     = 300;
	private boolean            renderStatic      = false;
	private List<HoverMessage> lastHoverMessages = List.of();
	
	private final ErrorLogLevel errorLogLevel = ConfigUtils.configEnum(CONFIG_PROPERTY_PREFIX + "tools.renderer.errorLogLevel", ErrorLogLevel.NAMED_STACK);
	
	
	private float   zoom = 1;
	private boolean transition;
	
	public BinaryGridRenderer(RenderBackend renderer){
		this.direct = renderer;
		buff = renderer.buffer();
		
		renderer.getDisplay().registerKeyboardButton(e -> {
			if(e.type() == RenderBackend.DisplayInterface.ActionType.UP) return;
			var amount = e.type() == RenderBackend.DisplayInterface.ActionType.HOLD? 0.05F : 0.02F;
			switch(e.key()){
				case GLFW_KEY_KP_ADD, GLFW_KEY_EQUAL -> zoom += amount;
				case GLFW_KEY_KP_SUBTRACT, GLFW_KEY_SLASH -> zoom = Math.max(1, zoom - amount);
				case GLFW_KEY_R -> {
					if(transition) return;
					transition = true;
					var frame = getFrame(getFramePos());
					if(frame != null){
						var prev = getPixelsPerByte();
						var now  = calcSize(frame);
						async(() -> {
							var frames = 20;
							for(int i = 0; i<frames; i++){
								var fac = (float)Math.pow(1 - (i + 1F)/frames, 2);
								pixelsPerByteChange(prev*fac + now*(1 - fac));
								UtilL.sleep(16);
							}
							notifyResize();
							transition = false;
						});
					}
				}
			}
			markDirty();
			markDirty();
		});
	}
	
	private int getFrameCount(){
		return getDisplayedSession().map(s -> {
			synchronized(s.frames){
				return s.frames.size();
			}
		}).orElse(0);
	}
	public SessionHost.CachedFrame getFrame(int index){
		return getDisplayedSession().map(s -> {
			synchronized(s.frames){
				if(index == -1){
					if(s.frames.isEmpty()) return null;
					return s.frames.getLast();
				}
				return s.frames.get(index);
			}
		}).orElse(null);
	}
	@Override
	public int getFramePos(){
		return getDisplayedSession().map(ses -> {
			if(ses.framePos.get() == -1){
				synchronized(ses.frames){
					return ses.frames.size() - 1;
				}
			}
			return ses.framePos.get();
		}).orElse(0);
	}
	
	@Override
	public void notifyResize(){
		var frame = getFrame(getFramePos());
		if(frame == null) return;
		pixelsPerByteChange(calcSize(frame));
		markDirty();
	}
	
	
	public float getPixelsPerByte(){
		return pixelsPerByte;
	}
	public void pixelsPerByteChange(float newPixelsPerByte){
		if(Math.abs(pixelsPerByte - newPixelsPerByte)<0.0001) return;
		pixelsPerByte = newPixelsPerByte;
		direct.markFrameDirty();
		markDirty();
	}
	
	private void outlineChunk(RenderContext ctx, Chunk chunk, Color color) throws IOException{
		long start = chunk.getPtr().getValue();
		long end   = chunk.dataEnd();
		
		DrawUtils.fillByteRange(ColorUtils.alpha(color, color.getAlpha()/255F*0.2F), ctx, new DrawUtils.Range(start, end));
		ctx.renderer.setLineWidth(2);
		outlineByteRange(color, ctx, new DrawUtils.Range(start, chunk.dataStart()));
		ctx.renderer.setLineWidth(3);
		outlineByteRange(color, ctx, new DrawUtils.Range(start, end));
		ctx.renderer.setLineWidth(1);
		var next = chunk.next();
		if(next != null){
			outlineChunk(ctx, next, ColorUtils.alpha(color, color.getAlpha()/255F*0.5F));
		}
	}
	
	public static void outlineByteRange(Color color, RenderContext ctx, DrawUtils.Range range){
		ctx.renderer.setColor(color);
		
		try(var ignored = ctx.renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
			for(var i = range.from(); i<range.to(); i++){
				long x  = i%ctx.width(), y = i/ctx.width();
				long x1 = x, y1 = y;
				long x2 = x1 + 1, y2 = y1 + 1;
				
				if(i - range.from()<ctx.width()) DrawUtils.drawPixelLine(ctx, x1, y1, x2, y1);
				if(range.to() - i<=ctx.width()) DrawUtils.drawPixelLine(ctx, x1, y2, x2, y2);
				if(x == 0 || i == range.from()) DrawUtils.drawPixelLine(ctx, x1, y1, x1, y2);
				if(x2 == ctx.width() || i == range.to() - 1) DrawUtils.drawPixelLine(ctx, x2, y1, x2, y2);
			}
		}
	}
	
	private <T extends IOInstance<T>> void annotateBitField(
		AnnotateCtx ctx,
		VarPool<T> ioPool, T instance, IOField<T, ?> field,
		Color col, int bitOffset, long bitSize, Reference reference, long fieldOffset
	) throws IOException{
		if(bitSize<8 && ctx.renderCtx.pixelsPerByte<8) return;
		var renderCtx = ctx.renderCtx;
		Consumer<DrawUtils.Rect> doSegment = bitRect -> {
			renderCtx.renderer.setColor(ColorUtils.alpha(col, 0.8F).darker());
			renderCtx.renderer.fillQuad(bitRect.x, bitRect.y, bitRect.width, bitRect.height);
			Optional<String> str;
			if(field instanceof IOFieldPrimitive.FBoolean<?> bf){
				str = Optional.of(((IOFieldPrimitive.FBoolean<T>)bf).getValue(ioPool, instance)? "√" : "x");
			}else{
				str = field.instanceToString(ioPool, instance, true);
			}
			str.ifPresent(s -> drawStringInInfo(renderCtx.renderer, col, s, bitRect, false, ctx.strings));
		};
		
		if(instance instanceof Chunk ch){
			var trueOffset = ch.getPtr().getValue() + fieldOffset;
			var remaining  = bitSize;
			var bitOff     = bitOffset;
			while(remaining>0){
				fillBitByte(renderCtx, trueOffset);
				var bitRect = DrawUtils.makeBitRect(ctx.renderCtx, trueOffset, bitOff, remaining);
				var range   = DrawUtils.Range.fromSize(trueOffset, 1);
				if(ctx.renderCtx.isRangeHovered(range)){
					ctx.renderCtx.hoverMessages.add(new HoverMessage(List.of(range), null, new Object[]{field + ": ", new FieldVal<>(ioPool, instance, field)}));
				}
				doSegment.accept(bitRect);
				remaining -= Math.min(8, remaining);
				bitOff = 0;
				trueOffset++;
			}
		}else{
			try(var io = new ChunkChainIO(reference.getPtr().dereference(ctx.provider))){
				io.setPos(reference.getOffset() + fieldOffset);
				
				var trueOffset = io.calcGlobalPos();
				var remaining  = bitSize;
				var bitOff     = bitOffset;
				while(remaining>0){
					fillBitByte(renderCtx, trueOffset);
					var bitRect = DrawUtils.makeBitRect(ctx.renderCtx, trueOffset, bitOff, remaining);
					var range   = DrawUtils.Range.fromSize(trueOffset, 1);
					if(ctx.renderCtx.isRangeHovered(range)){
						ctx.renderCtx.hoverMessages.add(new HoverMessage(List.of(range), null, new Object[]{field + ": ", new FieldVal<>(ioPool, instance, field)}));
					}
					doSegment.accept(bitRect);
					remaining -= Math.min(8, remaining);
					bitOff = 0;
					io.skipExact(1);
				}
			}
		}
	}
	private void fillBitByte(RenderContext ctx, long trueOffset){
		if(ctx.filled.get((int)trueOffset)) return;
		drawByteRanges(ctx, List.of(DrawUtils.Range.fromSize(trueOffset, 1)), Color.GREEN.darker(), false, true);
	}
	
	private <T extends IOInstance<T>> void annotateByteField(
		AnnotateCtx ctx,
		VarPool<T> ioPool, T instance, IOField<T, ?> field,
		Color col, Reference reference, DrawUtils.Range fieldRange
	){
		if(fieldRange.size() == 0) return;
		DrawUtils.Range hover     = null;
		DrawUtils.Range bestRange = new DrawUtils.Range(0, 0);
		DrawUtils.Range lastRange = null;
		var ranges = instance instanceof Chunk ch?
		             List.of(DrawUtils.Range.fromSize(ch.getPtr().getValue() + fieldRange.from(), fieldRange.size())) :
		             DrawUtils.chainRangeResolve(ctx.provider, reference, fieldRange.from(), fieldRange.size());
		for(DrawUtils.Range range : ranges){
			if(bestRange.size()<ctx.renderCtx.width()){
				var contiguousRange = DrawUtils.findBestContiguousRange(ctx.renderCtx.width, range);
				if(bestRange.size()<contiguousRange.size()) bestRange = contiguousRange;
			}
			if(lastRange != null) ctx.recordPointer(new Pointer(lastRange.to() - 1, range.from(), 0, col, field.toString(), 0.2F));
			lastRange = range;
			drawByteRanges(ctx.renderCtx, List.of(range), ColorUtils.alpha(ColorUtils.mul(col, 0.8F), 0.6F), false, true);
			if(hover == null && ctx.renderCtx.isRangeHovered(range)){
//				outlineByteRange(col, ctx.renderCtx, range);
//				DrawUtils.fillByteRange(alpha(col, 0.2F), ctx.renderCtx, range);
				hover = range;
			}
		}
		
		if(ctx.renderCtx.pixelsPerByte<6){
			return;
		}
		
		var color = ColorUtils.alpha(ColorUtils.mix(col, Color.WHITE, 0.2F), 1);
		
		var rectWidth = bestRange.toRect(ctx.renderCtx).width;
		
		var textData = ((Class<?>)field.getType()) == byte[].class && instance instanceof AutoText;
		
		Optional<String> str      = textData? Optional.of(((AutoText)instance).getData()) : field.instanceToString(ioPool, instance, false);
		Optional<String> shortStr = textData? str : Optional.empty();
		String           both;
		
		var fStr = field.toShortString();
		if(field instanceof RefField refField){
			//noinspection unchecked
			Reference ref;
			try{
				ref = refField.getReference(instance);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			if(ref != null && !ref.isNull()) fStr += " @ " + ref;
		}
		
		var renderCtx = ctx.renderCtx;
		both = fStr + (str.map(value -> ": " + value).orElse(""));
		if(!textData && str.isPresent() && getStringBounds(renderCtx.renderer, both).width()>rectWidth){
			shortStr = field.instanceToString(ioPool, instance, true);
			both = fStr + (shortStr.map(s -> ": " + s).orElse(""));
		}
		
		if(hover != null){
			ctx.renderCtx.hoverMessages.add(new HoverMessage(StreamUtil.stream(ranges).toList(), color, new Object[]{field + ": ", new FieldVal<>(ioPool, instance, field)}));
		}
		
		if(renderCtx.pixelsPerByte<6) return;
		
		if(str.isPresent() && getStringBounds(renderCtx.renderer, both).width()>rectWidth){
			if(renderCtx.pixelsPerByte>8){
				var font = renderCtx.renderer.getFontScale();
				initFont(renderCtx, 0.4F);
				var rect = bestRange.toRect(ctx.renderCtx);
				rect.y += renderCtx.renderer.getFontScale()*-0.8;
				drawStringInInfo(renderCtx.renderer, color, fStr, rect, false, ctx.strings);
				renderCtx.renderer.setFontScale(font);
			}
			
			if(getStringBounds(renderCtx.renderer, str.get()).width()>rectWidth){
				if(shortStr.isEmpty()) shortStr = field.instanceToString(ioPool, instance, true);
				str = shortStr;
			}
			var r = bestRange.toRect(ctx.renderCtx);
			r.y += ctx.renderCtx.pixelsPerByte*0.1F;
			
			str.ifPresent(s -> drawStringInInfo(renderCtx.renderer, color, s, r, false, ctx.strings, ctx.stringOutlines));
		}else{
			initFont(renderCtx, 1);
			drawStringInInfo(renderCtx.renderer, color, both, bestRange.toRect(ctx.renderCtx), false, ctx.strings, ctx.stringOutlines);
		}
	}
	
	private void drawLine(RenderContext ctx, long from, long to){
		long xPosFrom = from%ctx.width(), yPosFrom = from/ctx.width();
		long xPosTo   = to%ctx.width(), yPosTo = to/ctx.width();
		
		DrawUtils.drawPixelLine(ctx, xPosFrom + 0.5, yPosFrom + 0.5, xPosTo + 0.5, yPosTo + 0.5);
	}
	
	private static final Color CHUNK_BASE_COLOR = Color.GRAY;
	
	private void fillChunk(RenderContext ctx, Chunk chunk, boolean withChar, boolean force){
		var chunkColor = CHUNK_BASE_COLOR;
		var dataColor  = ColorUtils.alpha(ColorUtils.mul(chunkColor, 0.5F), 0.7F);
		var freeColor  = ColorUtils.alpha(chunkColor, 0.4F);
		
		int start = (int)chunk.dataStart();
		int cap   = (int)chunk.getCapacity();
		int siz   = (int)chunk.getSize();
		drawByteRanges(ctx, List.of(new DrawUtils.Range(start + siz, start + cap)), freeColor, withChar, force);
		drawByteRanges(ctx, List.of(new DrawUtils.Range(start, start + siz)), dataColor, withChar, force);
	}
	
	private void fillBit(RenderContext ctx, float x, float y, int index, float xOff, float yOff){
		int   xi  = index%3;
		int   yi  = index/3;
		float pxS = ctx.pixelsPerByte()/3F;
		
		float x1 = xi*pxS;
		float y1 = yi*pxS;
		float x2 = (xi + 1)*pxS;
		float y2 = (yi + 1)*pxS;
		
		ctx.renderer.fillQuad(x + xOff + x1, y + yOff + y1, x2 - x1, y2 - y1);
	}
	
	private void initFont(RenderContext ctx){
		initFont(ctx, 0.8F);
	}
	
	private void initFont(RenderContext ctx, float sizeMul){
		ctx.renderer.setFontScale(ctx.pixelsPerByte()*sizeMul);
	}
	
	private void drawStringIn(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean doStroke){
		drawStringIn(renderer, color, s, area, doStroke, false);
	}
	
	private void drawStringIn(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean doStroke, boolean alignLeft){
		var info = drawStringInInfo(renderer, color, s, area, alignLeft);
		if(info == null) return;
		
		renderer.getFont().fillStrings(info);
		if(doStroke){
			renderer.getFont().outlineStrings(new DrawFont.StringDraw(info.pixelHeight(), info.xScale(), new Color(0, 0, 0, 0.5F), info.string(), info.x(), info.y()));
		}
	}
	
	private void drawStringInInfo(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean alignLeft, List<DrawFont.StringDraw> strings, List<DrawFont.StringDraw> stringStrokes){
		var info = drawStringInInfo(renderer, color, s, area, alignLeft);
		if(info != null){
			strings.add(info);
			stringStrokes.add(new DrawFont.StringDraw(info.pixelHeight(), info.xScale(), new Color(0, 0, 0, 0.5F), info.string(), info.x(), info.y()));
		}
	}
	private void drawStringInInfo(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean alignLeft, List<DrawFont.StringDraw> strings){
		var info = drawStringInInfo(renderer, color, s, area, alignLeft);
		if(info != null) strings.add(info);
	}
	private DrawFont.StringDraw drawStringInInfo(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean alignLeft){
		if(s.isEmpty()) return null;
		
		float fontScale = renderer.getFontScale();
		try{
			if(area.height<fontScale){
				renderer.setFontScale(area.height);
			}
			
			float w, h;
			{
				var rect = getStringBounds(renderer, s);
				
				w = rect.width();
				h = rect.height();
				
				if(w>0){
					double scale = (area.width - 1)/w;
					if(scale<0.5){
						float div = scale<0.25? 3 : 2;
						renderer.setFontScale(renderer.getFontScale()/div);
						w = rect.width()/div;
						h = rect.height()/div;
					}
					DrawFont.Bounds sbDots = null;
					while((area.width - 1)/w<0.5){
						if(s.isEmpty()) return null;
						if(s.length() == 1){
							break;
						}
						s = s.substring(0, s.length() - 1).trim();
						rect = getStringBounds(renderer, s);
						if(sbDots == null){
							sbDots = getStringBounds(renderer, "...");
						}
						w = rect.width() + sbDots.width();
						h = Math.max(rect.height(), sbDots.height());
					}
				}
			}
			
			float x = area.x;
			float y = area.y;
			
			x += alignLeft? 0 : Math.max(0, area.width - w)/2D;
			y += h + (area.height - h)/2;
			
			float xScale = 1;
			if(w>0){
				double scale = (area.width - 1)/w;
				if(scale<1){
					xScale = (float)scale;
				}
			}
			
			return new DrawFont.StringDraw(renderer.getFontScale(), xScale, color, s, x, y);
		}finally{
			renderer.setFontScale(fontScale);
		}
	}
	
	private float calcSize(SessionHost.CachedFrame frame){
		return calcSize(direct.getDisplay(), frame.memData().bytes().length, true);
	}
	private float calcSize(RenderBackend.DisplayInterface displayInterface, int bytesCount, boolean restart){
		var screenHeight = displayInterface.getHeight();
		var screenWidth  = displayInterface.getWidth();
		
		int columns;
		if(restart) columns = 1;
		else{
			columns = (int)(screenWidth/getPixelsPerByte() + 0.0001F);
		}
		
		while(true){
			float newPixelsPerByte = (screenWidth - 0.5F)/columns;
			
			float width          = screenWidth/newPixelsPerByte;
			int   rows           = (int)Math.ceil(bytesCount/width);
			int   requiredHeight = (int)Math.ceil(rows*newPixelsPerByte);
			
			if(screenHeight<requiredHeight){
				columns++;
			}else{
				break;
			}
		}
		
		return (screenWidth - 0.5F)/columns;
	}
	
	private void handleError(Throwable e, SessionHost.ParsedFrame parsed){
		if(errorMode){
			if(parsed.displayError == null) parsed.displayError = e;
			switch(errorLogLevel){
				case NAME -> warn("{}", e);
				case STACK -> e.printStackTrace();
				case NAMED_STACK -> new RuntimeException("Failed to process frame " + getFramePos(), e).printStackTrace();
			}
		}else throw UtilL.uncheckedThrow(e);
	}
	
	@Override
	public List<HoverMessage> render(){
		frameTimer.start();
		
		renderCount++;
		try{
			errorMode = false;
			return render(getFramePos());
		}catch(Throwable e){
			errorMode = true;
			try{
				return render(getFramePos());
			}catch(Throwable e1){
				e1.printStackTrace();
			}
		}
		
		frameTimer.end();
		trace("Frame time: {}", (Supplier<Double>)frameTimer::msAvrg100);
		return List.of();
	}
	private void startFrame(RenderContext ctx){
		ctx.renderer.clearFrame();
		ctx.renderer.initRenderState();
		initFont(ctx);
		
		
		var d = ctx.renderer.getDisplay();
		if(zoom>1.0001){
			markDirty();
		}
		var zoom = this.zoom*this.zoom;
		
		var w    = d.getWidth();
		var h    = d.getHeight();
		var offX = (w - w*zoom)*(d.getMouseX()/(float)w);
		var offY = (h - h*zoom)*(d.getMouseY()/(float)h);
		
		ctx.renderer.translate(--offX, offY);
		
		drawBackgroundDots(ctx.renderer);
	}
	
	private List<HoverMessage> lastActuallyHoveredMessages = List.of();
	private List<HoverMessage> render(int frameIndex){
		if(getFrameCount() == 0){
			renderNoData(direct);
			renderStatic = false;
			return List.of();
		}
		
		var zoom = this.zoom*this.zoom;
		
		if(errorMode) markDirty();
		
		SessionHost.CachedFrame cFrame = getFrame(frameIndex);
		MemFrame                frame  = cFrame.memData();
		var                     bytes  = frame.bytes();
		
		if(bytes.length == 0){
			renderNoData(direct);
			renderStatic = false;
			return List.of();
		}
		
		var dis = direct.getDisplay();
		pixelsPerByteChange(calcSize(dis, bytes.length, false));
		
		SessionHost.ParsedFrame parsed = cFrame.parsed();
		if(!errorMode){
			parsed.displayError = null;
		}
		if(!renderStatic){
			var ctx = new RenderContext(null, bytes, getPixelsPerByte(), zoom, dis, null);
			
			var actuallyHoveredMessages =
				lastHoverMessages.stream().filter(m -> m.ranges().stream().anyMatch(ctx::isRangeHovered)).toList();
			
			if(!actuallyHoveredMessages.equals(lastActuallyHoveredMessages)){
				lastActuallyHoveredMessages = actuallyHoveredMessages;
				markDirty();
			}
		}
		
		if(renderStatic || RenderBackend.DRAW_DEBUG){
			renderStatic = false;
			buff.clear();
			
			var rCtx = new RenderContext(RenderBackend.DRAW_DEBUG? direct : buff, bytes, getPixelsPerByte(), zoom, dis, new ArrayList<>());
			
			findHoverChunk(rCtx, parsed, DataProvider.newVerySimpleProvider(MemoryData.viewOf(bytes)));
			
			drawStatic(frame, rCtx, parsed);
			
			if(parsed.lastHoverChunk != null){
				Random r = new Random(parsed.lastHoverChunk.getPtr().getValue());
				rCtx.hoverMessages.add(new HoverMessage(
					List.of(new DrawUtils.Range(parsed.lastHoverChunk.getPtr().getValue(), parsed.lastHoverChunk.dataEnd())),
					new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), 100),
					new Object[]{parsed.lastHoverChunk}
				));
			}
			this.lastHoverMessages = List.copyOf(rCtx.hoverMessages);
		}
		
		var ctx = new RenderContext(direct, bytes, getPixelsPerByte(), zoom, dis, new ArrayList<>(lastHoverMessages));
		
		buff.draw();
		
		findHoverChunk(ctx, parsed, DataProvider.newVerySimpleProvider(MemoryData.viewOf(bytes)));
		
		if(!ctx.renderer.getDisplay().isMouseKeyDown(RenderBackend.DisplayInterface.MouseKey.LEFT)){
			drawMouse(ctx, cFrame);
		}
		
		return ctx.hoverMessages;
	}
	private void renderNoData(RenderBackend renderer){
		startFrame(new RenderContext(
			renderer,
			null, null,
			0, getPixelsPerByte(), 0, 0, 0,
			new ArrayList<>()
		));
		var str = "No data!";
		
		int w = renderer.getDisplay().getWidth(), h = renderer.getDisplay().getHeight();
		renderer.setFontScale(Math.min(h, w/(str.length()*0.8F)));
		drawStringIn(renderer, Color.LIGHT_GRAY, str, new DrawUtils.Rect(0, 0, w, h), true);
	}
	@Override
	public void markDirty(){
		renderStatic = true;
	}
	@Override
	public boolean isDirty(){
		return renderStatic;
	}
	private void drawStatic(MemFrame frame, RenderContext ctx, SessionHost.ParsedFrame parsed){
		int frameIndex = parsed.index;
		startFrame(ctx);
		
		byte[] bytes = ctx.bytes;
		var    magic = MagicID.get();
		
		var hasMagic = bytes.length>=magic.limit() && magic.mismatch(ByteBuffer.wrap(bytes).limit(magic.limit())) == -1;
		if(!hasMagic && !errorMode){
			throw new RuntimeException("No magic bytes");
		}
		
		if(hasMagic){
			drawByteRanges(ctx, List.of(new DrawUtils.Range(0, magic.limit())), Color.BLUE, false, true);
		}else{
			IntPredicate isValidMagicByte = i -> bytes.length>i && magic.get(i) == bytes[i];
			drawBytes(ctx, IntStream.range(0, magic.limit()).filter(isValidMagicByte), Color.BLUE, true, true);
			drawBytes(ctx, IntStream.range(0, magic.limit()).filter(isValidMagicByte.negate()), Color.RED, true, true);
		}
		ctx.renderer.setLineWidth(2F);
		outlineByteRange(Color.WHITE, ctx, new DrawUtils.Range(0, magic.limit()));
		drawStringIn(ctx.renderer, Color.WHITE, new String(bytes, 0, Math.min(bytes.length, magic.limit())), new DrawUtils.Rect(0, 0, ctx.pixelsPerByte()*Math.min(magic.limit(), ctx.width()), ctx.pixelsPerByte()), false);
		
		ctx.renderer.setColor(ColorUtils.alpha(Color.WHITE, 0.5F));
		
		List<Pointer> ptrs = new ArrayList<>();
		Consumer<Pointer> pointerRecord =
			RenderBackend.DRAW_DEBUG?
			p -> {
				drawPointers(ctx, parsed, List.of(p));
				ptrs.add(p);
			} :
			ptrs::add;
		
		ChunkSet referenced = new ChunkSet();
		try{
			Cluster cluster = parsed.getCluster().orElseGet(() -> {
				try{
					var c = new Cluster(MemoryData.viewOf(bytes));
					trace("parsed cluster at frame {}", frameIndex);
					parsed.cluster = new WeakReference<>(c);
					return c;
				}catch(Exception e){
					handleError(new RuntimeException("failed to read cluster on frame " + frameIndex, e), parsed);
				}
				return null;
			});
			if(cluster != null){
				DataProvider provider = cluster;
				var          cl       = cluster;
				var          root     = cluster.rootWalker(null, false).getRoot();
				
				
				List<DrawFont.StringDraw> strings, stringOutlines;
				if(RenderBackend.DRAW_DEBUG){
					strings = new ArrayList<>(){
						@Override
						public boolean add(DrawFont.StringDraw stringDraw){
							ctx.renderer.getFont().fillStrings(List.of(stringDraw));
							return true;
						}
					};
					stringOutlines = new ArrayList<>(){
						@Override
						public boolean add(DrawFont.StringDraw stringDraw){
							ctx.renderer.getFont().outlineStrings(List.of(stringDraw));
							return true;
						}
					};
				}else{
					strings = new ArrayList<>();
					stringOutlines = new ArrayList<>();
				}
				
				Throwable e1     = null;
				var       annCtx = new AnnotateCtx(ctx, provider, new LinkedList<>(), pointerRecord, strings, stringOutlines);
				
				try{
					boolean[] logged = {false};
					Consumer<Throwable> log = e -> {
						if(logged[0]) return;
						logged[0] = true;
						e.printStackTrace();
					};
					try{
						cluster.rootWalker(MemoryWalker.PointerRecord.of(ref -> {
							if(!ref.isNull()){
								try{
									for(Chunk chunk : ref.getPtr().dereference(cl).walkNext()){
										referenced.add(chunk.getPtr());
									}
								}catch(Throwable e){
									log.accept(e);
								}
							}
						}), true).walk();
					}catch(Throwable e){
						log.accept(e);
					}
					
					annotateStruct(annCtx, root,
					               cluster.getFirstChunk().getPtr().makeReference(),
					               FixedStructPipe.of(root.getThisStruct()),
					               null, true, false);
					
				}catch(Throwable e){
					e1 = e;
				}
				try{
					assert annCtx.stack.isEmpty();
					annCtx.stack.add(null);
					
					var  done  = new ChunkSet();
					var  frees = new ChunkSet(provider.getMemoryManager().getFreeChunks());
					long pos   = cluster.getFirstChunk().getPtr().getValue();
					while(pos<bytes.length){
						try{
							for(Chunk chunk : new PhysicalChunkWalker(cluster.getChunk(ChunkPointer.of(pos)))){
								pos = chunk.dataEnd();
								if(referenced.contains(chunk.getPtr())){
									if(frees.contains(chunk.getPtr())){
										drawByteRanges(ctx,
										               List.of(new DrawUtils.Range(chunk.dataStart(), Math.min(cluster.getSource().getIOSize(), chunk.dataEnd()))),
										               ColorUtils.alpha(Color.YELLOW, 0.15F), false, false);
									}else{
										fillChunk(ctx, chunk, true, false);
									}
								}else{
									drawByteRanges(ctx,
									               List.of(new DrawUtils.Range(chunk.dataStart(), Math.min(cluster.getSource().getIOSize(), chunk.dataEnd()))),
									               ColorUtils.alpha(Color.BLUE, 0.1F), false, false);
								}
								if(done.add(chunk.getPtr())){
									chunk.addChainToPtr(done);
									annotateChunk(annCtx, chunk);
								}
							}
						}catch(IOException e){
							var p = (int)pos;
							drawBytes(ctx, IntStream.of(p), Color.RED, true, true);
							pos++;
						}
					}
					annCtx.popStrings(ctx.renderer);
				}catch(Throwable e){
					if(e1 != null) e1.addSuppressed(e);
					else e1 = e;
				}
				
				if(e1 != null) throw e1;
			}else{
				var         provider = DataProvider.newVerySimpleProvider(MemoryData.of(bytes));
				AnnotateCtx annCtx   = new AnnotateCtx(ctx, provider, new LinkedList<>(), pointerRecord, new ArrayList<>(), new ArrayList<>());
				annCtx.stack.add(null);
				long pos;
				try{
					pos = provider.getFirstChunk().getPtr().getValue();
				}catch(Throwable e){
					pos = magic.limit();
				}
				while(pos<bytes.length){
					try{
						for(Chunk chunk : new PhysicalChunkWalker(provider.getChunk(ChunkPointer.of(pos)))){
							pos = chunk.dataEnd();
							annotateChunk(annCtx, chunk);
						}
					}catch(IOException e){
						var p = (int)pos;
						drawBytes(ctx, IntStream.of(p), Color.RED, true, true);
						pos++;
					}
				}
				annCtx.popStrings(ctx.renderer);
			}
		}catch(Throwable e){
			handleError(e, parsed);
		}
		
		drawBytes(ctx, IntStream.range(0, bytes.length).filter(((IntPredicate)ctx.filled::get).negate()), ColorUtils.alpha(Color.GRAY, 0.5F), true, true);
		
		drawWriteIndex(frame, ctx);
		
		drawPointers(ctx, parsed, ptrs);
		
		drawTimeline(ctx.renderer, frameIndex);
	}
	
	private void annotateChunk(AnnotateCtx ctx, Chunk chunk) throws IOException{
		annotateStruct(ctx, chunk, null, Chunk.PIPE, null, true, false);
		var rctx = ctx.renderCtx;
		if(chunk.dataEnd()>rctx.bytes.length){
			drawByteRanges(rctx, List.of(new DrawUtils.Range(rctx.bytes.length, chunk.dataEnd())), new Color(0, 0, 0, 0.2F), false, false);
		}
	}
	
	private void drawBytes(RenderContext ctx, IntStream stream, Color color, boolean withChar, boolean force){
		drawByteRanges(ctx, DrawUtils.Range.fromInts(stream), color, withChar, force);
	}
	
	private void drawByteRanges(RenderContext ctx, List<DrawUtils.Range> ranges, Color color, boolean withChar, boolean force){
		List<DrawUtils.Range> actualRanges;
		if(force) actualRanges = ranges;
		else actualRanges = DrawUtils.Range.filterRanges(ranges, i -> !ctx.filled.get((int)i));
		
		drawByteRangesForced(ctx, actualRanges, color, withChar);
	}
	
	private void drawByteRangesForced(RenderContext ctx, List<DrawUtils.Range> ranges, Color color, boolean withChar){
		var col        = ColorUtils.mul(color, 0.8F);
		var bitColor   = col;
		var background = ColorUtils.mul(col, 0.6F);
		
		Consumer<IterablePP<DrawUtils.Range>> drawIndex = r -> {
			try(var ignored = ctx.renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
				r.forEach(range -> DrawUtils.fillByteRange(ctx, range));
			}
		};
		
		List<DrawUtils.Range> clampedOverflow = DrawUtils.Range.clamp(ranges, ctx.bytes.length);
		
		Supplier<IntStream> clampedInts = () -> DrawUtils.Range.toInts(clampedOverflow);
		
		
		ctx.renderer.setColor(background);
		try(var ignored = ctx.renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
			for(DrawUtils.Range range : clampedOverflow){
				DrawUtils.fillByteRange(ctx, range);
			}
		}
		
		drawIndex.accept(Iters.from(clampedOverflow));
		
		ctx.renderer.setColor(ColorUtils.alpha(Color.RED, color.getAlpha()/255F));
		drawIndex.accept(Iters.from(ranges).map(r -> {
			if(r.to()<ctx.bytes.length) return null;
			if(r.from()<ctx.bytes.length) return new DrawUtils.Range(ctx.bytes.length, r.to());
			return r;
		}).nonNulls());
		
		ctx.renderer.setColor(bitColor);
		try(var ignored = ctx.renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
			for(var range : clampedOverflow){
				for(int pos = Math.toIntExact(range.from()), iend = Math.toIntExact(range.to()); pos<iend; pos++){
					var i = pos;
					int b = ctx.bytes[i]&0xFF;
					if(b == 0) continue;
					int   xi = i%ctx.width(), yi = i/ctx.width();
					float xF = ctx.pixelsPerByte()*xi, yF = ctx.pixelsPerByte()*yi;
					if(ctx.pixelsPerByte()<6){
						ctx.renderer.fillQuad(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()/8F*Integer.bitCount(b));
					}else{
						for(int bi = 0; bi<8; bi++){
							if(((b>>bi)&1) == 1){
								fillBit(ctx, xF, yF, bi, 0, 0);
							}
						}
					}
				}
			}
		}
		
		if(withChar){
			var c = new Color(1, 1, 1, bitColor.getAlpha()/255F*0.6F);
			
			List<DrawFont.StringDraw> chars = null;
			if(clampedOverflow.size() == 1){
				var r = clampedOverflow.getFirst();
				if(r.size() == 1){
					var i = (int)r.from();
					if(ctx.renderer.getFont().canFontDisplay(ctx.bytes[i])){
						int   xi   = i%ctx.width(), yi = i/ctx.width();
						float xF   = ctx.pixelsPerByte()*xi, yF = ctx.pixelsPerByte()*yi;
						var   info = drawStringInInfo(ctx.renderer, c, Character.toString((char)(ctx.bytes[i]&0xFF)), new DrawUtils.Rect(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()), false);
						if(info != null) chars = List.of(info);
					}
				}
			}
			
			if(chars == null){
				chars = clampedInts.get().filter(i -> ctx.renderer.getFont().canFontDisplay(ctx.bytes[i])).mapToObj(i -> {
					int   xi = i%ctx.width(), yi = i/ctx.width();
					float xF = ctx.pixelsPerByte()*xi, yF = ctx.pixelsPerByte()*yi;
					
					return drawStringInInfo(ctx.renderer, c, Character.toString((char)(ctx.bytes[i]&0xFF)), new DrawUtils.Rect(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()), false);
				}).filter(Objects::nonNull).toList();
			}
			if(!chars.isEmpty()){
				ctx.renderer.getFont().fillStrings(chars);
			}
		}
		for(var range : clampedOverflow){
			ctx.filled.set((int)range.from(), (int)range.to());
		}
	}
	private void drawBackgroundDots(RenderBackend renderer){
		renderer.setColor(errorMode? Color.RED.darker() : Color.LIGHT_GRAY);
		
		var screenHeight = renderer.getDisplay().getHeight();
		var screenWidth  = renderer.getDisplay().getWidth();
		
		try(var ignored = renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
			float jitter       = 4;
			int   step         = 15;
			float randX        = renderCount/20f;
			float randY        = renderCount/20f + 10000;
			float simplexScale = 50;
			for(int x = 0; x<screenWidth + 2; x += step){
				for(int y = (x/step)%step; y<screenHeight + 2; y += step){
					float xf = x/simplexScale;
					float yf = y/simplexScale;
					renderer.fillQuad(x + SimplexNoise.noise(xf, yf, randX)*jitter, y + SimplexNoise.noise(xf, yf, randY)*jitter, 1.5, 1.5);
				}
			}
		}
	}
	
	private void drawTimeline(RenderBackend renderer, int frameIndex){
		if(getFrameCount()<2) return;
		
		var screenHeight = renderer.getDisplay().getHeight();
		var screenWidth  = renderer.getDisplay().getWidth();
		
		renderer.pushMatrix();
		renderer.translate(0, screenHeight);
		renderer.scale(1, -1);
		
		double w = screenWidth/(double)getFrameCount();
		
		double height = 6;
		
		renderer.setColor(Color.BLUE.darker());
		renderer.fillQuad(frameIndex*w, 0, w, height*1.5);
		renderer.fillQuad(frameIndex*w - 0.75, 0, 1.5, height*1.5);
		
		renderer.setColor(ColorUtils.alpha(Color.WHITE, 0.3F));
		renderer.fillQuad(w, 0, screenWidth, height);
		renderer.popMatrix();
	}
	
	private void drawWriteIndex(MemFrame frame, RenderContext ctx){
		ctx.renderer.setColor(Color.YELLOW);
		for(long id : frame.ids()){
			if(id>=frame.bytes().length) continue;
			int i  = (int)id;
			int xi = i%ctx.width();
			int yi = i/ctx.width();
			
			fillBit(ctx, 0, 0, 8, xi*ctx.pixelsPerByte(), yi*ctx.pixelsPerByte());
		}
	}
	
	private void drawPointers(RenderContext ctx, SessionHost.ParsedFrame parsed, List<Pointer> ptrs){
//		if(true) return;
		var                       renderer = ctx.renderer;
		List<DrawFont.StringDraw> strings  = new ArrayList<>(ptrs.size());
		for(Pointer ptr : ptrs){
			boolean drawMsg = false;
			
			var siz   = ctx.pixelsPerByte()/32F;
			var alpha = Math.min(1, siz);
			siz = Math.max(1, siz);
			var sFul  = siz;
			var sHalf = siz/2;
			renderer.setLineWidth(sFul*ptr.widthFactor());
			
			var start = ptr.from();
			var end   = ptr.to();
			
			int pSiz = ptr.size();
			
			Color col;
			if(parsed.lastHoverChunk != null && new ChainWalker(parsed.lastHoverChunk).anyMatch(ch -> ch.rangeIntersects(ptr.from()))){
				col = ColorUtils.mix(CHUNK_BASE_COLOR, ptr.color(), 0.5F);
				drawMsg = true;
				renderer.setLineWidth(sFul*ptr.widthFactor()*2);
				
			}else{
				col = ColorUtils.alpha(ptr.color(), 0.5F);
			}
			
			renderer.setColor(ColorUtils.alpha(col, 0.7F*alpha));
			
			if(pSiz>1 && LongStream.range(start, start + pSiz).noneMatch(i -> i%ctx.width() == 0)){
				renderer.setColor(ColorUtils.alpha(col, 0.2F*alpha));
				renderer.setLineWidth(sHalf*ptr.widthFactor());
				drawLine(ctx, start, start + pSiz - 1);
				renderer.setLineWidth(sFul*ptr.widthFactor());
				renderer.setColor(ColorUtils.alpha(col, 0.7F*alpha));
			}
			
			start += pSiz/2;
			
			long
				xPosFrom = start%ctx.width(),
				yPosFrom = start/ctx.width(),
				xPosTo = end%ctx.width(),
				yPosTo = end/ctx.width();
			
			var direction = start<end;
			var ySmall    = Math.abs(yPosFrom - yPosTo)>1;
			var dirY      = direction? 1 : -1;
			var dirX      = 0;
			
			double
				offScale = ySmall? Math.abs(yPosFrom - yPosTo)/6D : 2,
				xFromOff = (dirX)*offScale,
				yFromOff = (dirY)*offScale,
				xToOff = (-dirX)*offScale,
				yToOff = ((ySmall? -1 : 1)*dirY)*offScale,
				
				xFromOrg = xPosFrom + (pSiz == 0? 0 : 0.5),
				yFromOrg = yPosFrom + 0.5,
				xToOrg = xPosTo + 0.5,
				yToOrg = yPosTo + 0.5,
				
				xFrom = xFromOrg + xFromOff,
				yFrom = yFromOrg + yFromOff,
				xTo = xToOrg + xToOff,
				yTo = yToOrg + yToOff;
			
			var screenHeight = renderer.getDisplay().getHeight();
			var screenWidth  = renderer.getDisplay().getWidth();
			
			if(xFrom<0 || xFrom>screenWidth) xFrom = xFromOrg - xFromOff;
			if(yFrom<0 || yFrom>screenHeight) yFrom = yFromOrg - yFromOff;
			if(xTo<0 || xTo>screenWidth) xTo = xToOrg - xToOff;
			if(yTo<0 || yTo>screenHeight) yTo = yToOrg - yToOff;
			
			DrawUtils.drawPath(ctx, new double[][]{
				{xFromOrg, yFromOrg},
				{xFrom, yFrom},
				{xTo, yTo},
				{xToOrg, yToOrg}
			}, true);
			
			if(drawMsg && !ptr.message().isEmpty()){
				float
					x = (float)(xFrom + xTo)/2*ctx.pixelsPerByte(),
					y = (float)(yFrom + yTo)/2*ctx.pixelsPerByte();
				initFont(ctx, 0.3F*ptr.widthFactor());
				renderer.setFontScale(Math.max(renderer.getFontScale(), 15));
				int msgWidth = ptr.message().length();
				int space    = (int)(screenWidth - x);
				
				var w = getStringBounds(renderer, ptr.message()).width();
				while(w>space*1.5){
					msgWidth--;
					if(msgWidth == 0) break;
					w = getStringBounds(renderer, ptr.message().substring(0, msgWidth)).width();
				}
				List<String> lines = msgWidth == 0? List.of(ptr.message()) : TextUtil.wrapLongString(ptr.message(), msgWidth);
				y -= renderer.getLineWidth()/2F*lines.size();
				for(String line : lines){
					drawStringInInfo(renderer, col, line, new DrawUtils.Rect(x, y, space, ctx.pixelsPerByte()), true, strings);
					y += renderer.getLineWidth();
				}
			}
		}
		renderer.getFont().fillStrings(strings);
	}
	
	private void drawError(RenderContext ctx, SessionHost.ParsedFrame parsed){
		if(parsed.displayError == null) return;
		var renderer     = ctx.renderer;
		var screenHeight = renderer.getDisplay().getHeight();

//		parsed.displayError.printStackTrace();
		initFont(ctx, 0.2F);
		renderer.setFontScale(Math.max(renderer.getFontScale(), 12));
		
		
		var msg        = DrawUtils.errorToMessage(parsed.displayError);
		var lines      = msg.split("\n");
		var bounds     = Arrays.stream(lines).map(s -> getStringBounds(renderer, s)).toList();
		var totalBound = bounds.stream().reduce((l, r) -> new DrawFont.Bounds(Math.max(l.width(), r.width()), l.height() + r.height())).orElseThrow();
		
		renderer.setColor(ColorUtils.alpha(Color.RED.darker(), 0.2F));
		renderer.fillQuad(0, screenHeight - totalBound.height() - 25, totalBound.width() + 20, totalBound.height() + 20);
		
		var col  = ColorUtils.alpha(Color.WHITE, 0.8F);
		var rect = new DrawUtils.Rect(10, screenHeight - totalBound.height() - 20, totalBound.width(), renderer.getLineWidth());
		
		List<DrawFont.StringDraw> strings = new ArrayList<>(lines.length);
		
		for(int i = 0; i<lines.length; i++){
			String line  = lines[i];
			var    bound = bounds.get(i);
			rect.height = bound.height();
			rect.y = (Math.round(screenHeight - totalBound.height() + bounds.stream().limit(i).mapToDouble(DrawFont.Bounds::height).sum()) - 15);
			drawStringInInfo(renderer, col, line, rect, true, strings);
		}
		renderer.getFont().fillStrings(strings);
	}
	
	private void drawMouse(RenderContext ctx, SessionHost.CachedFrame frame){
		var bytes  = frame.memData().bytes();
		var parsed = frame.parsed();
		
		int byteIndex = ctx.hoverByteIndex;
		if(byteIndex == -1) return;
		
		record CRange(Color col, DrawUtils.Range rang){ }
		
		var              flatRanges = new ArrayList<CRange>();
		Map<Long, Color> colorMap   = new HashMap<>();
		
		for(int i = ctx.hoverMessages.size() - 1; i>=0; i--){
			HoverMessage hoverMessage = ctx.hoverMessages.get(i);
			if(hoverMessage.color() == null || hoverMessage.isRangeEmpty()){
				continue;
			}
			hoverMessage.ranges().stream().flatMapToLong(DrawUtils.Range::longs).forEach(l -> colorMap.put(l, hoverMessage.color()));
		}
		
		colorMap.entrySet().stream().sorted(Comparator.comparingLong(Map.Entry::getKey)).forEach(e -> {
			if(flatRanges.isEmpty()){
				flatRanges.add(new CRange(e.getValue(), DrawUtils.Range.fromSize(e.getKey(), 1)));
				return;
			}
			var  last = flatRanges.getLast();
			long pos  = e.getKey();
			if(last.col.equals(e.getValue()) && pos == last.rang.to()){
				flatRanges.set(flatRanges.size() - 1, new CRange(e.getValue(), new DrawUtils.Range(last.rang.from(), e.getKey() + 1)));
				return;
			}
			
			flatRanges.add(new CRange(e.getValue(), DrawUtils.Range.fromSize(e.getKey(), 1)));
		});
		
		if(parsed.lastHoverChunk != null){
			var chunk = parsed.lastHoverChunk;
			ctx.renderer.setLineWidth(1);
			try{
				outlineChunk(ctx, chunk, ColorUtils.mix(CHUNK_BASE_COLOR, Color.WHITE, 0.4F));
			}catch(IOException e){
				handleError(e, parsed);
			}
		}
		
		for(var range : flatRanges){
			DrawUtils.fillByteRange(ColorUtils.alpha(range.col, 0.2F), ctx, range.rang);
		}
		
		for(int i = ctx.hoverMessages.size() - 1; i>=0; i--){
			HoverMessage hoverMessage = ctx.hoverMessages.get(i);
			if(hoverMessage.color() == null || hoverMessage.isRangeEmpty()){
				continue;
			}
			ctx.renderer.setLineWidth(1);
			for(DrawUtils.Range range : hoverMessage.ranges()){
				outlineByteRange(hoverMessage.color(), ctx, range);
			}
		}
		
		var           b    = bytes[byteIndex]&0xFF;
		StringBuilder bStr = new StringBuilder(String.valueOf(b));
		while(bStr.length()<3) bStr.append(" ");
		bStr.append(ctx.renderer.getFont().canFontDisplay(bytes[byteIndex])? " = " + (char)b : "");
		ctx.hoverMessages().addAll(0, List.of(new HoverMessage(List.of(new DrawUtils.Range(0, 0)), null, new Object[]{
			parsed.lastHoverChunk != null && byteIndex>=parsed.lastHoverChunk.dataStart()? new Reference(parsed.lastHoverChunk.getPtr(), byteIndex - parsed.lastHoverChunk.dataStart()).toString() : "",
			"@" + byteIndex
		}), new HoverMessage(List.of(new DrawUtils.Range(0, 0)), null, new Object[]{bStr.toString()})));
		
		ctx.renderer.setLineWidth(3);
		outlineByteRange(Color.BLACK, ctx, DrawUtils.Range.fromSize(byteIndex, 1));
		
		ctx.renderer.setColor(Color.WHITE);
		ctx.renderer.setLineWidth(1);
		outlineByteRange(Color.WHITE, ctx, DrawUtils.Range.fromSize(byteIndex, 1));
		
	}
	
	private DrawFont.Bounds getStringBounds(RenderBackend renderer, String s){
		return renderer.getFont().getStringBounds(s, renderer.getFontScale());
	}
	
	private void findHoverChunk(RenderContext ctx, SessionHost.ParsedFrame parsed, DataProvider provider){
		int byteIndex = ctx.hoverByteIndex;
		if(byteIndex == -1){
			if(parsed.lastHoverChunk != null) markDirty();
			parsed.lastHoverChunk = null;
			return;
		}
		
		try{
			if(parsed.lastHoverChunk == null || !parsed.lastHoverChunk.rangeIntersects(byteIndex)){
				parsed.lastHoverChunk = null;
				for(Chunk chunk : new PhysicalChunkWalker(provider.getFirstChunk())){
					if(chunk.rangeIntersects(byteIndex)){
						markDirty();
						parsed.lastHoverChunk = chunk;
						break;
					}
				}
			}
		}catch(IOException e){
			if(parsed.lastHoverChunk != null) markDirty();
			parsed.lastHoverChunk = null;
			ctx.hoverMessages.add(new HoverMessage(List.of(new DrawUtils.Range(0, 0)), null, new Object[]{"Unable to find hover chunk"}));
		}
	}
	
	private record AnnotateStackFrame(IOInstance<?> instance, Reference ref){ }
	
	private record AnnotateCtx(RenderContext renderCtx,
	                           DataProvider provider, List<AnnotateStackFrame> stack,
	                           Consumer<Pointer> pointerRecord, List<DrawFont.StringDraw> strings, List<DrawFont.StringDraw> stringOutlines){
		void recordPointer(Pointer pointer){
			pointerRecord.accept(pointer);
		}
		void popStrings(RenderBackend renderer){
			if(!strings.isEmpty()){
				renderer.getFont().fillStrings(strings);
				strings.clear();
			}
			if(!stringOutlines.isEmpty()){
				renderer.getFont().outlineStrings(stringOutlines);
				stringOutlines.clear();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> void annotateStruct(
		AnnotateCtx ctx,
		T instance, Reference instanceReference, StructPipe<T> pipe,
		GenericContext parentGenerics, boolean annotate,
		boolean noPtr) throws IOException{
		var reference = instanceReference;
		if(instance instanceof Chunk c){
			var off = ctx.provider.getFirstChunk().getPtr();
			reference = new Reference(off, c.getPtr().getValue() - off.getValue());
		}
		if(reference == null || reference.isNull()) return;
		
		var                renderer = ctx.renderCtx.renderer;
		AnnotateStackFrame frame    = new AnnotateStackFrame(instance, reference);
		try{
			if(ctx.stack.contains(frame)) return;
			ctx.stack.add(frame);
			
			var typeHash = instance.getThisStruct().getFullName().hashCode();
			
			var rctx = ctx.renderCtx;
			
			RuntimeException fieldErr = null;
			var              rand     = new RawRandom();
			renderer.setLineWidth(4);
			
			Iterator<IOField<T, ?>> iterator = pipe.getSpecificFields().iterator();
			
			long fieldOffset = 0;
			long offsetStart;
			
			if(instance instanceof Chunk){
				offsetStart = reference.getPtr().add(reference.getOffset());
			}else{
				offsetStart = reference.calcGlobalOffset(ctx.provider);
			}
			
			var unmanagedStage = false;
			
			var ioPool = instance.getThisStruct().allocVirtualVarPool(IO);
			if(ioPool != null){
				try(var io = reference.io(ctx.provider)){
					var virtuals = FieldSet.of(pipe.getSpecificFields().filtered(f -> f.iterUnpackedFields().anyMatch(f2 -> f2.isVirtual(IO))));
					var dep      = pipe.getFieldDependency();
					var deps     = dep.getDeps(virtuals);
					
					if(deps.readFields().iter().flatMap(IOField::iterUnpackedFields).anyMatch(IOField::isReadOnly)){
						var bpipe = pipe.getBuilderPipe();
						var type  = bpipe.getType();
						var bdeps = bpipe.getFieldDependency().getDeps(deps.readFields().iter().map(IOField::getName));
						
						var builder = type.make();
						var pool    = bpipe.makeIOPool();
						bpipe.readDeps(pool, ctx.provider, io, bdeps, builder, generics(instance, parentGenerics));
						
						for(IOField virtual : virtuals.iter().flatMap(IOField::iterUnpackedFields).filter(f -> f.isVirtual(IO))){
							virtual.set(ioPool, null, type.getFields().requireByName(virtual.getName()).get(pool, builder));
						}
					}else{
						pipe.readDeps(ioPool, ctx.provider, io, deps, instance, generics(instance, parentGenerics));
					}
				}catch(Throwable e){
					for(var gen : instance.getThisStruct().getFields().flatMapped(IOField::getGenerators)){
						gen.generate(ioPool, ctx.provider, instance, false);
					}
					
					//TODO: Re-enable this and never generate fields. Reading was causing issues with some lists. Find out why
//					var size = 1L;
//					try{
//						size = pipe.calcUnknownSize(ctx.provider, instance, WordSpace.BYTE);
//					}catch(Throwable ignored){ }
//					drawByteRangesForced(ctx.renderCtx, List.of(DrawUtils.Range.fromSize(reference.calcGlobalOffset(ctx.provider), size)), Color.RED, false);
//
//					throw new TypeIOFail("Failed to recover virtual fields", instance.getClass(), e);
				}
			}
			while(true){
				if(!iterator.hasNext()){
					if(instance instanceof IOInstance.Unmanaged<?> unmanaged){
						if(!unmanagedStage){
							iterator = (Iterator<IOField<T, ?>>)(Object)unmanaged.listUnmanagedFields().iterator();
							unmanagedStage = true;
						}else break;
						continue;
					}else break;
				}
				
				var field = (IOField<T, Object>)iterator.next();
				try{
					var col = ColorUtils.makeCol(typeHash, field);
					
					final long size;
					
					long      trueOffset = offsetStart + fieldOffset;
					final var sizeDesc   = field.getSizeDescriptor();
					size = sizeDesc.calcUnknown(ioPool, ctx.provider, instance, sizeDesc.getWordSpace());
					
					try{
						var acc = field.getAccessor();
						
						if(acc != null && field.typeFlag(IOField.DYNAMIC_FLAG) && !(field instanceof RefField)){
							
							var inst = field.get(ioPool, instance);
							if(inst == null) continue;
							if(SupportedPrimitive.isAny(inst.getClass()) || inst.getClass() == String.class){
								if(annotate)
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
								continue;
							}
							if(inst instanceof IOInstance.Unmanaged<?> unmanaged){
								var        ref = unmanaged.getPointer().makeReference();
								StructPipe pip = unmanaged.getPipe();
								annotateStruct(ctx, (T)unmanaged, ref, pip, generics(instance, parentGenerics), annotate, noPtr);
								if(annotate){
									var bSize = sizeDesc.mapSize(WordSpace.BYTE, size);
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, bSize));
								}
								continue;
							}
							if(inst instanceof IOInstance<?> ioi){
								StructPipe pip = getStructPipe(instance, pipe, unmanagedStage, field, ioi);
								annotateStruct(ctx, (T)ioi, reference.addOffset(fieldOffset), pip, generics(instance, parentGenerics), annotate, noPtr);
								continue;
							}
							if(inst instanceof IOInstance<?>[] arr){
								var len = annotateDynamicArrayValueHead(ctx, instance, reference, fieldOffset, ioPool, field, col, arr);
								if(arr.length>0){
									var        arrayOffset = fieldOffset + len + NumberSize.bySize(arr.length).bytes;
									StructPipe pip         = getStructPipe(instance, pipe, unmanagedStage, field, arr[0]);
									var        gens        = generics(instance, parentGenerics);
									for(var val : arr){
										annotateStruct(ctx, (T)val, reference.addOffset(arrayOffset), pip, gens, annotate, noPtr);
										arrayOffset += pip.calcUnknownSize(ctx.provider, val, WordSpace.BYTE);
									}
								}
								continue;
							}
							if(inst.getClass().isArray() || UtilL.instanceOf(inst.getClass(), List.class)){
								var ahead       = annotateDynamicArrayValueHead(ctx, instance, reference, fieldOffset, ioPool, field, col, inst);
								var arrayOffset = fieldOffset + ahead;
								if(annotate)
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(arrayOffset, size - ahead));
								continue;
							}
							warn("unmanaged dynamic type {}", inst);
							continue;
						}
						
						if(field instanceof RefField refO){
							RefField<T, ?> refField = (RefField<T, ?>)refO;
							var            ref      = refField.getReference(instance);
							boolean        diffPos  = true;
							Pointer        ptr      = null;
							var            bSize    = sizeDesc.mapSize(WordSpace.BYTE, size);
							
							if(!ref.isNull() && !noPtr){
								long from, ptrSize = bSize;
								var  refContainer  = bSize == 0? pipe.getSpecificFields().byName(FieldNames.ref(acc)) : OptionalPP.<IOField<T, ?>>empty();
								if(refContainer.isPresent()){
									var refF      = refContainer.get();
									var refOffset = 0L;
									for(var sf : pipe.getSpecificFields()){
										if(sf == refF) break;
										refOffset += sf.getSizeDescriptor().calcUnknown(ioPool, ctx.provider, instance, WordSpace.BYTE);
									}
									from = reference.addOffset(refOffset).calcGlobalOffset(ctx.provider);
									ptrSize = refF.getSizeDescriptor().calcUnknown(ioPool, ctx.provider, instance, WordSpace.BYTE);
								}else{
									from = reference.addOffset(fieldOffset).calcGlobalOffset(ctx.provider);
								}
								var to = ref.calcGlobalOffset(ctx.provider);
								diffPos = from != to;
								if(diffPos){
									ptr = new Pointer(from, to, (int)ptrSize, col, refField.toString(), 0.8F);
								}
							}
							try{
								if(annotate){
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, bSize));
								}
							}catch(Throwable e){
								if(ptr != null){
									ptr = new Pointer(ptr.from, ptr.to, ptr.size, Color.RED, ptr.message, 1);
								}
								throw e;
							}finally{
								if(ptr != null){
									ctx.recordPointer(ptr);
								}
							}
							if(!diffPos){
								int xByte     = (int)(renderer.getDisplay().getMouseX()/rctx.pixelsPerByte());
								int yByte     = (int)(renderer.getDisplay().getMouseY()/rctx.pixelsPerByte());
								int byteIndex = yByte*rctx.width() + xByte;
								
								var from = trueOffset;
								var to   = from + size;
								if(from<=byteIndex && byteIndex<to){
									diffPos = true;
								}
							}
							if(!diffPos) ctx.popStrings(renderer);
							
							if(refField instanceof RefField.Inst instRef){
								if(!ref.isNull()){
									annotateStruct(ctx, (T)refField.get(ioPool, instance), ref, instRef.getReferencedPipe(instance), generics(instance, parentGenerics), diffPos, noPtr);
								}
							}else{
								ObjectPipe pip      = refField.getReferencedPipe(instance);
								var        refVal   = refField.get(null, instance);
								var        dataSize = pip.calcUnknownSize(ctx.provider, refVal, WordSpace.BYTE);
								annotateByteField(ctx, ioPool, instance, refField, col, ref, DrawUtils.Range.fromSize(0, dataSize));
							}
							
							continue;
						}
						if(field instanceof BitFieldMerger<T> merger){
							int bitOffset = 0;
							drawByteRanges(rctx, List.of(DrawUtils.Range.fromSize(trueOffset, size)), col, false, true);
							if(rctx.pixelsPerByte>8){
								for(BitField<T, ?> bit : merger.fieldGroup()){
									
									var bCol = ColorUtils.makeCol(typeHash, bit);
									var siz  = bit.getSizeDescriptor().calcUnknown(ioPool, ctx.provider, instance, WordSpace.BIT);
									
									if(annotate)
										annotateBitField(ctx, ioPool, instance, bit, bCol, bitOffset%8, siz, reference, fieldOffset + bitOffset/8);
									bitOffset += siz;
								}
							}
							continue;
						}
						if(field instanceof CollectionAdapter.CollectionContainer<?, ?> container){
							var addapter  = container.getCollectionAddapter();
							var colletion = field.get(ioPool, instance);
							switch(addapter.getElementIO()){
								case CollectionAdapter.ElementIOImpl.PipeImpl pipeImpl -> {
									long arrOffset = 0;
									var  gen       = generics(instance, parentGenerics);
									for(var el : ((CollectionAdapter<IOInstance, Object>)addapter).asListView(colletion)){
										if(el == null) continue;
										var elSize = pipeImpl.calcByteSize(ctx.provider, el);
										
										StructPipe elementPipe = StandardStructPipe.of(el.getThisStruct());
										annotateStruct(ctx, el, reference.addOffset(fieldOffset + arrOffset), elementPipe, gen, annotate, noPtr);
										arrOffset += elSize;
									}
									continue;
								}
								case CollectionAdapter.ElementIOImpl.SealedTypeImpl pipeImpl -> {
									long arrOffset = 0;
									var  gen       = generics(instance, parentGenerics);
									int  arrPow    = -1;
									for(var el : ((CollectionAdapter<IOInstance, Object>)addapter).asListView(colletion)){
										arrPow++;
										if(el == null) continue;
										var type = el.getClass();
										int id;
										try{
											id = ctx.provider.getTypeDb().toID(pipeImpl.getRoot(), type, false);
										}catch(IOException e){
											throw new RuntimeException("Failed to compute ID", e);
										}
										var idBytes = 1 + NumberSize.bySize(id).bytes;
										annotateByteField(
											ctx,
											ioPool,
											instance,
											new NoIOField<>(
												new BasicFieldAccessor<T>(null, field.getName() + "[" + arrPow + "]:type", List.of()){
													@Override
													public int getTypeID(){ return TypeFlag.ID_OBJECT; }
													@Override
													public boolean genericTypeHasArgs(){
														return false;
													}
													@Override
													public Object get(VarPool<T> ioPool, T instance){ return type; }
													@Override
													public void set(VarPool<T> ioPool, T instance, Object value){ throw new UnsupportedOperationException(); }
													@Override
													public boolean isReadOnly(){ return true; }
													@Override
													public Type getGenericType(GenericContext genericContext){
														return Class.class;
													}
												},
												SizeDescriptor.Fixed.of(idBytes)
											){
												@Override
												public Optional<String> instanceToString(VarPool<T> ioPool, T instance, StringifySettings settings){
													return Optional.of(settings.doShort()? type.getSimpleName() : type.getTypeName());
												}
											},
											col,
											reference.addOffset(fieldOffset + arrOffset),
											DrawUtils.Range.fromSize(arrOffset, idBytes)
										);
										arrOffset += idBytes;
										
										var elSize = pipeImpl.calcByteSize(ctx.provider, el);
										
										StructPipe elementPipe = StandardStructPipe.of(el.getThisStruct());
										annotateStruct(ctx, el, reference.addOffset(fieldOffset + arrOffset), elementPipe, gen, annotate, noPtr);
										arrOffset += elSize;
									}
									continue;
								}
								default -> { }
							}
						}
						if(acc == null){
							throw new RuntimeException("unknown field " + field);
						}
						
						if(UtilL.instanceOf(acc.getType(), ChunkPointer.class)){
							
							var ch = (ChunkPointer)field.get(ioPool, instance);
							
							if(annotate) annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
							
							if(!ch.isNull()){
								var msg = field.toString();
								try{
									ctx.popStrings(renderer);
									annotateStruct(ctx, ch.dereference(ctx.provider), null, Chunk.PIPE, generics(instance, parentGenerics), true, noPtr);
								}catch(Exception e){
									msg = msg + "\n" + DrawUtils.errorToMessage(e);
									col = Color.RED;
								}
								if(!noPtr) ctx.recordPointer(new Pointer(trueOffset, ch.getValue(), (int)size, col, msg, 0.8F));
							}
						}else if(SupportedPrimitive.isAny(acc.getType()) ||
						         Stream.of(
							         ChunkPointer.class, Enum.class,
							         Duration.class, Instant.class,
							         LocalDate.class, LocalTime.class, LocalDateTime.class
						         ).anyMatch(c -> UtilL.instanceOf(acc.getType(), c))){
							if(annotate){
								renderer.setColor(col);
								if(sizeDesc.getWordSpace() == WordSpace.BIT && size<8){
									annotateBitField(ctx, ioPool, instance, field, col, 0, size, reference, fieldOffset);
								}else{
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, sizeDesc.mapSize(WordSpace.BYTE, size)));
								}
							}
						}else{
							var typ = acc.getType();
							if(typ == Object.class){
								var inst = field.get(ioPool, instance);
								if(inst == null){
									continue;
								}else{
									typ = inst.getClass();
								}
							}
							if(IOInstance.isInstance(typ)){
								var inst = (IOInstance<?>)field.get(ioPool, instance);
								if(inst != null){
									StructPipe pip    = getStructPipe(instance, pipe, unmanagedStage, field, inst);
									var        noPtrs = noPtr;
									if(!noPtrs && inst instanceof Reference &&
									   pipe.getSpecificFields()
									       .stream()
									       .map(f -> FieldNames.ref(FieldNames.name(f.getName())))
									       .anyMatch(n -> n.equals(acc.getName()))
									){
										noPtrs = true;
									}
									annotateStruct(ctx, (T)inst, reference.addOffset(fieldOffset), pip, generics(instance, parentGenerics), annotate, noPtrs);
								}
								continue;
							}
							
							boolean isList = typ == List.class || typ == ArrayList.class;
							if(typ.isArray() || isList){
								
								Class<?> comp;
								if(isList){
									var gTyp = acc.getGenericType(parentGenerics);
									comp = switch(gTyp){
										case ParameterizedType p -> Utils.typeToRaw(p.getActualTypeArguments()[0]);
										case Class<?> c -> Object.class;
										default -> throw new NotImplementedException(gTyp.getClass().getName());
									};
								}else{
									comp = typ.componentType();
								}
								
								if(IOInstance.isManaged(comp)){
									Object instTmp = field.get(ioPool, instance);
									if(instTmp == null) continue;
									List<IOInstance<?>> inst = (List<IOInstance<?>>)(isList? instTmp : ArrayViewList.create((Object[])instTmp, null));
									if(inst.isEmpty()) continue;
									
									StructPipe elementPipe = StandardStructPipe.of(Struct.ofUnknown(inst.getFirst().getClass()));
									long       arrOffset   = 0;
									for(IOInstance val : inst){
										annotateStruct(ctx, val, reference.addOffset(fieldOffset + arrOffset), elementPipe, generics(instance, parentGenerics), annotate, noPtr);
										arrOffset += elementPipe.getSizeDescriptor().calcUnknown(elementPipe.makeIOPool(), ctx.provider, val, WordSpace.BYTE);
									}
									continue;
								}
								if(comp == float.class){
									var inst = (float[])field.get(ioPool, instance);
									if(inst == null){
										annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
										continue;
									}
									var arrSiz = inst.length;
									
									if(size == arrSiz*4L){
										long arrOffset = 0;
										for(int i = 0; i<arrSiz; i++){
											annotateByteField(
												ctx, ioPool, instance, BGRUtils.floatArrayElement(instance, i, inst),
												col,
												reference,
												DrawUtils.Range.fromSize(fieldOffset + arrOffset, 4));
											arrOffset += 4;
										}
										continue;
									}
								}
								if(comp == String.class){
									var o = field.get(ioPool, instance);
									if(o == null){
										annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
										continue;
									}
									var inst = isList? (List<String>)o : Arrays.asList((String[])o);
									if(inst == null) continue;
									var arrSiz = inst.size();
									
									long arrOffset = 0;
									for(int i = 0; i<arrSiz; i++){
										var siz = AutoText.Info.PIPE.calcUnknownSize(ctx.provider, new AutoText(inst.get(i)), WordSpace.BYTE);
										annotateByteField(
											ctx, ioPool, instance, BGRUtils.stringArrayElement(instance, i, inst),
											col,
											reference,
											DrawUtils.Range.fromSize(fieldOffset + arrOffset, siz));
										arrOffset += siz;
									}
									continue;
								}
								if(comp.isEnum()){
									var o = field.get(ioPool, instance);
									if(o == null){
										annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
										continue;
									}
									List<Enum> inst   = isList? (List<Enum>)o : Arrays.asList((Enum[])o);
									var        arrSiz = inst.size();
									
									int bitOffset = 0;
									drawByteRanges(rctx, List.of(DrawUtils.Range.fromSize(trueOffset, size)), col, false, true);
									if(!annotate) continue;
									
									var universe  = EnumUniverse.ofUnknown(comp);
									var arrOffset = 0;
									for(int i = 0; i<arrSiz; i++){
										var bCol = ColorUtils.makeCol(typeHash, field);
										var bit  = BGRUtils.enumListElement(instance, i, inst, comp);
										annotateBitField(ctx, ioPool, instance, bit, bCol, bitOffset, universe.bitSize, reference, fieldOffset + arrOffset);
										bitOffset += universe.bitSize;
										while(bitOffset>=8){
											bitOffset -= 8;
											arrOffset++;
										}
									}
									continue;
								}
								
								if(SupportedPrimitive.isAny(comp)){
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
									continue;
								}
							}
							if(field instanceof IOFieldFusedString){
								if(!annotate) continue;
								annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
								return;
							}
							if(typ == String.class){
								if(!annotate) continue;
								try{
									annotateStruct(ctx, new AutoText((String)field.get(ioPool, instance)), reference.addOffset(fieldOffset), AutoText.Info.PIPE, null, true, noPtr);
								}catch(Throwable e){
									annotateByteField(ctx, ioPool, instance, field, col, reference, DrawUtils.Range.fromSize(fieldOffset, size));
								}
								continue;
							}
							
							warn("unmanaged draw type: {} accessor: {}", typ, acc);
						}
					}finally{
						fieldOffset += sizeDesc.mapSize(WordSpace.BYTE, size);
					}
				}catch(Throwable e){
					String instStr = instanceErrStr(instance);
					var    err     = new RuntimeException("failed to annotate " + field + " at " + reference.addOffset(fieldOffset) + " in " + instStr + " at " + reference, e);
					e.printStackTrace();
					if(fieldErr == null){
						fieldErr = err;
					}else{
						fieldErr.addSuppressed(err);
					}
					var sizeDesc = field.getSizeDescriptor();
					var size     = sizeDesc.calcUnknown(ioPool, ctx.provider, instance, WordSpace.BYTE);
					outlineByteRange(Color.RED, ctx.renderCtx, DrawUtils.Range.fromSize(offsetStart + fieldOffset - size, size));
				}
			}
			if(fieldErr != null){
				throw fieldErr;
			}
			
			var ranges = instance instanceof Chunk? List.of(DrawUtils.Range.fromSize(offsetStart, fieldOffset)) : DrawUtils.chainRangeResolve(ctx.provider, reference, 0, fieldOffset);
			for(DrawUtils.Range range : ranges){
				if(rctx.isRangeHovered(range)){
					rctx.hoverMessages.add(new HoverMessage(StreamUtil.stream(ranges).toList(), ColorUtils.makeCol(typeHash, offsetStart + ""), new Object[]{"Inst: ", instance}));
					break;
				}
			}
		}finally{
			ctx.stack.remove(frame);
			if(ctx.stack.isEmpty()){
				ctx.popStrings(renderer);
			}
		}
	}
	private static <T extends IOInstance<T>> StructPipe getStructPipe(
		IOInstance instance, StructPipe<T> pipe, boolean unmanagedStage,
		IOField<T, Object> field, IOInstance<? extends IOInstance<?>> inst
	){
		if(unmanagedStage){
			return ((IOInstance.Unmanaged)instance).getFieldPipe(field, inst);
		}else{
			if(field instanceof IOFieldInlineObject obj){
				return obj.getInstancePipe();
			}
			return StructPipe.of(pipe.getClass(), inst.getThisStruct());
		}
	}
	
	private <T extends IOInstance<T>> int annotateDynamicArrayValueHead(AnnotateCtx ctx, T instance, Reference reference, long fieldOffset, VarPool<T> ioPool, IOField<T, Object> field, Color col, Object arr) throws IOException{
		CollectionInfo.Store info;
		var                  ref = reference.addOffset(fieldOffset);
		try{
			info = ref.ioMap(ctx.provider, io -> CollectionInfo.Store.PIPE.readNew(ctx.provider, io, null));
		}catch(Throwable e){
			throw new IOException("Failed to read " + CollectionInfo.class.getSimpleName() + " on " + ref, e);
		}
		annotateStruct(ctx, info, ref, CollectionInfo.Store.PIPE, null, true, false);
		return (int)info.val().calcIOBytes(ctx.provider);
	}
	
	private <T extends IOInstance<T>> GenericContext generics(T instance, GenericContext parentGenerics){
		return instance instanceof IOInstance.Unmanaged<?> u? u.getGenerics() : null;
	}
	
	private <T extends IOInstance<T>> String instanceErrStr(T instance){
		String instStr;
		try{
			instStr = instance.toString();
		}catch(Throwable e1){
			instStr = "<err toString " + e1.getMessage() + " for " + instance.getClass().getName() + ">";
		}
		return instStr;
	}
	
	@Override
	public Optional<SessionHost.HostedSession> getDisplayedSession(){
		return displayedSession;
	}
	@Override
	public void setDisplayedSession(Optional<SessionHost.HostedSession> displayedSession){
		this.displayedSession = displayedSession;
		markDirty();
	}
}
