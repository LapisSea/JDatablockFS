package com.lapissea.cfs.tools;


import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.MagicID;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.*;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.ObjectPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.tools.DrawUtils.Range;
import com.lapissea.cfs.tools.SessionHost.CachedFrame;
import com.lapissea.cfs.tools.SessionHost.ParsedFrame;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.tools.render.RenderBackend;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.AbstractFieldAccessor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.access.TypeFlag;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IODynamic;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.*;
import org.joml.SimplexNoise;
import org.roaringbitmap.RoaringBitmap;

import java.awt.Color;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.logging.Log.trace;
import static com.lapissea.cfs.logging.Log.warn;
import static com.lapissea.cfs.tools.ColorUtils.alpha;
import static com.lapissea.cfs.tools.render.RenderBackend.DrawMode;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;
import static com.lapissea.util.PoolOwnThread.async;
import static com.lapissea.util.UtilL.TRUE;
import static org.lwjgl.glfw.GLFW.*;

@SuppressWarnings({"UnnecessaryLocalVariable", "SameParameterValue", "rawtypes", "unchecked"})
public class BinaryGridRenderer implements DataRenderer{
	
	static{
		Thread.ofVirtual().start(()->{
			try{
				Cluster.emptyMem().rootWalker(MemoryWalker.PointerRecord.NOOP, false).walk();
			}catch(IOException ignored){}
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
		byte[] bytes, RoaringBitmap filled,
		int width, float pixelsPerByte, int hoverByteX, int hoverByteY, int hoverByteIndex,
		List<HoverMessage> hoverMessages){
		
		private static int calcIndex(byte[] bytes, RenderBackend.DisplayInterface dis, float pixelsPerByte, float zoom){
			int width    =(int)Math.max(1, dis.getWidth()/pixelsPerByte);
			int xByte    =calcX(dis, pixelsPerByte, zoom);
			int yByte    =calcY(dis, pixelsPerByte, zoom);
			int byteIndex=yByte*width+xByte;
			if(xByte>=width||byteIndex>=bytes.length) return -1;
			else return byteIndex;
		}
		
		RenderContext(RenderBackend renderer,
		              byte[] bytes,
		              float pixelsPerByte, float zoom, RenderBackend.DisplayInterface dis,
		              List<HoverMessage> hoverMessages){
			this(renderer, bytes, new RoaringBitmap(),
			     (int)Math.max(1, dis.getWidth()/pixelsPerByte), pixelsPerByte*zoom,
			     calcX(dis, pixelsPerByte, zoom),
			     calcY(dis, pixelsPerByte, zoom),
			     calcIndex(bytes, dis, pixelsPerByte, zoom),
			     hoverMessages);
		}
		
		private static int calcY(RenderBackend.DisplayInterface d, float pixelsPerByte, float zoom){
			var h   =d.getHeight();
			var offY=(h-h*zoom)*(d.getMouseY()/(float)h);
			return (int)((d.getMouseY()-offY)/(pixelsPerByte*zoom));
		}
		private static int calcX(RenderBackend.DisplayInterface d, float pixelsPerByte, float zoom){
			var w   =d.getWidth();
			var offX=(w-w*zoom)*(d.getMouseX()/(float)w);
			return (int)((d.getMouseX()-offX)/(pixelsPerByte*zoom));
		}
		
		boolean isRangeHovered(Range range){
			var f=range.from();
			var t=range.to();
			return f<=hoverByteIndex&&hoverByteIndex<t;
		}
		
	}
	
	private record Pointer(long from, long to, int size, Color color, String message, float widthFactor){}
	
	private boolean errorMode;
	private long    renderCount;
	
	//	private       RenderBackend          renderer;
	private final RenderBackend          direct;
	private final RenderBackend.Buffered buff;
	
	private final NanoTimer frameTimer=new NanoTimer();
	
	private Optional<SessionHost.HostedSession> displayedSession=Optional.empty();
	
	private float              pixelsPerByte    =300;
	private boolean            renderStatic     =false;
	private List<HoverMessage> lastHoverMessages=List.of();
	
	private final ErrorLogLevel errorLogLevel=GlobalConfig.configEnum("tools.renderer.errorLogLevel", ErrorLogLevel.NAMED_STACK);
	
	
	private float   zoom=1;
	private boolean transition;
	
	public BinaryGridRenderer(RenderBackend renderer){
		this.direct=renderer;
		buff=renderer.buffer();
		
		renderer.getDisplay().registerKeyboardButton(e->{
			if(e.type()==RenderBackend.DisplayInterface.ActionType.UP) return;
			var amount=e.type()==RenderBackend.DisplayInterface.ActionType.HOLD?0.05F:0.02F;
			switch(e.key()){
				case GLFW_KEY_KP_ADD, GLFW_KEY_EQUAL -> zoom+=amount;
				case GLFW_KEY_KP_SUBTRACT, GLFW_KEY_SLASH -> zoom=Math.max(1, zoom-amount);
				case GLFW_KEY_R -> {
					if(transition) return;
					transition=true;
					var frame=getFrame(getFramePos());
					if(frame!=null){
						var prev=getPixelsPerByte();
						var now =calcSize(frame);
						async(()->{
							var frames=20;
							for(int i=0;i<frames;i++){
								var fac=(float)Math.pow(1-(i+1F)/frames, 2);
								pixelsPerByteChange(prev*fac+now*(1-fac));
								UtilL.sleep(16);
							}
							notifyResize();
							transition=false;
						});
					}
				}
			}
			markDirty();
			markDirty();
		});
	}
	
	private int getFrameCount(){
		return getDisplayedSession().map(s->{
			synchronized(s.frames){
				return s.frames.size();
			}
		}).orElse(0);
	}
	public SessionHost.CachedFrame getFrame(int index){
		return getDisplayedSession().map(s->{
			synchronized(s.frames){
				if(index==-1){
					if(s.frames.isEmpty()) return null;
					return s.frames.get(s.frames.size()-1);
				}
				return s.frames.get(index);
			}
		}).orElse(null);
	}
	@Override
	public int getFramePos(){
		return getDisplayedSession().map(ses->{
			if(ses.framePos.get()==-1){
				synchronized(ses.frames){
					ses.setFrame(ses.frames.size()-1);
				}
			}
			return ses.framePos.get();
		}).orElse(0);
	}
	
	@Override
	public void notifyResize(){
		var frame=getFrame(getFramePos());
		if(frame==null) return;
		pixelsPerByteChange(calcSize(frame));
		markDirty();
	}
	
	
	public float getPixelsPerByte(){
		return pixelsPerByte;
	}
	public void pixelsPerByteChange(float newPixelsPerByte){
		if(Math.abs(pixelsPerByte-newPixelsPerByte)<0.0001) return;
		pixelsPerByte=newPixelsPerByte;
		direct.markFrameDirty();
		markDirty();
	}
	
	private void outlineChunk(RenderContext ctx, Chunk chunk, Color color) throws IOException{
		long start=chunk.getPtr().getValue();
		long end  =chunk.dataEnd();
		
		DrawUtils.fillByteRange(alpha(color, color.getAlpha()/255F*0.2F), ctx, new Range(start, end));
		ctx.renderer.setLineWidth(2);
		outlineByteRange(color, ctx, new Range(start, chunk.dataStart()));
		ctx.renderer.setLineWidth(3);
		outlineByteRange(color, ctx, new Range(start, end));
		ctx.renderer.setLineWidth(1);
		var next=chunk.next();
		if(next!=null){
			outlineChunk(ctx, next, alpha(color, color.getAlpha()/255F*0.5F));
		}
	}
	
	public static void outlineByteRange(Color color, RenderContext ctx, Range range){
		ctx.renderer.setColor(color);
		
		try(var ignored=ctx.renderer.bulkDraw(DrawMode.QUADS)){
			for(var i=range.from();i<range.to();i++){
				long x =i%ctx.width(), y=i/ctx.width();
				long x1=x, y1=y;
				long x2=x1+1, y2=y1+1;
				
				if(i-range.from()<ctx.width()) DrawUtils.drawPixelLine(ctx, x1, y1, x2, y1);
				if(range.to()-i<=ctx.width()) DrawUtils.drawPixelLine(ctx, x1, y2, x2, y2);
				if(x==0||i==range.from()) DrawUtils.drawPixelLine(ctx, x1, y1, x1, y2);
				if(x2==ctx.width()||i==range.to()-1) DrawUtils.drawPixelLine(ctx, x2, y1, x2, y2);
			}
		}
	}
	
	private <T extends IOInstance<T>> void annotateBitField(
		AnnotateCtx ctx,
		VarPool<T> ioPool, T instance, IOField<T, ?> field,
		Color col, int bitOffset, long bitSize, Reference reference, long fieldOffset
	) throws IOException{
		var renderCtx=ctx.renderCtx;
		Consumer<DrawUtils.Rect> doSegment=bitRect->{
			renderCtx.renderer.setColor(alpha(col, 0.8F).darker());
			renderCtx.renderer.fillQuad(bitRect.x, bitRect.y, bitRect.width, bitRect.height);
			Optional<String> str;
			if(field instanceof IOFieldPrimitive.FBoolean<?> bf){
				str=Optional.of(((IOFieldPrimitive.FBoolean<T>)bf).getValue(ioPool, instance)?"√":"x");
			}else{
				str=field.instanceToString(ioPool, instance, true);
			}
			str.ifPresent(s->drawStringInInfo(renderCtx.renderer, col, s, bitRect, false, ctx.strings));
		};
		
		if(instance instanceof Chunk ch){
			var trueOffset=ch.getPtr().getValue()+fieldOffset;
			var remaining =bitSize;
			var bitOff    =bitOffset;
			while(remaining>0){
				fillBitByte(renderCtx, trueOffset);
				var bitRect=DrawUtils.makeBitRect(ctx.renderCtx, trueOffset, bitOff, remaining);
				var range  =Range.fromSize(trueOffset, 1);
				if(ctx.renderCtx.isRangeHovered(range)){
					ctx.renderCtx.hoverMessages.add(new HoverMessage(List.of(range), null, new Object[]{field+": ", new FieldVal<>(ioPool, instance, field)}));
				}
				doSegment.accept(bitRect);
				remaining-=Math.min(8, remaining);
				bitOff=0;
				trueOffset++;
			}
		}else{
			try(var io=new ChunkChainIO(reference.getPtr().dereference(ctx.provider))){
				io.setPos(reference.getOffset()+fieldOffset);
				
				var trueOffset=io.calcGlobalPos();
				var remaining =bitSize;
				var bitOff    =bitOffset;
				while(remaining>0){
					fillBitByte(renderCtx, trueOffset);
					var bitRect=DrawUtils.makeBitRect(ctx.renderCtx, trueOffset, bitOff, remaining);
					var range  =Range.fromSize(trueOffset, 1);
					if(ctx.renderCtx.isRangeHovered(range)){
						ctx.renderCtx.hoverMessages.add(new HoverMessage(List.of(range), null, new Object[]{field+": ", new FieldVal<>(ioPool, instance, field)}));
					}
					doSegment.accept(bitRect);
					remaining-=Math.min(8, remaining);
					bitOff=0;
					io.skipExact(1);
				}
			}
		}
	}
	private void fillBitByte(RenderContext ctx, long trueOffset){
		if(ctx.filled.contains((int)trueOffset)) return;
		drawByteRanges(ctx, List.of(Range.fromSize(trueOffset, 1)), Color.GREEN.darker(), false, true);
	}
	
	private <T extends IOInstance<T>> void annotateByteField(
		AnnotateCtx ctx,
		VarPool<T> ioPool, T instance, IOField<T, ?> field,
		Color col, Reference reference, Range fieldRange
	){
		Range hover    =null;
		Range bestRange=new Range(0, 0);
		Range lastRange=null;
		var ranges=instance instanceof Chunk ch?
		           List.of(Range.fromSize(ch.getPtr().getValue()+fieldRange.from(), fieldRange.size())):
		           DrawUtils.chainRangeResolve(ctx.provider, reference, fieldRange.from(), fieldRange.size());
		for(Range range : ranges){
			if(bestRange.size()<ctx.renderCtx.width()){
				var contiguousRange=DrawUtils.findBestContiguousRange(ctx.renderCtx.width, range);
				if(bestRange.size()<contiguousRange.size()) bestRange=contiguousRange;
			}
			if(lastRange!=null) ctx.recordPointer(new Pointer(lastRange.to()-1, range.from(), 0, col, field.toString(), 0.2F));
			lastRange=range;
			drawByteRanges(ctx.renderCtx, List.of(range), alpha(ColorUtils.mul(col, 0.8F), 0.6F), false, true);
			if(hover==null&&ctx.renderCtx.isRangeHovered(range)){
//				outlineByteRange(col, ctx.renderCtx, range);
//				DrawUtils.fillByteRange(alpha(col, 0.2F), ctx.renderCtx, range);
				hover=range;
			}
		}
		
		var color=alpha(ColorUtils.mix(col, Color.WHITE, 0.2F), 1);
		
		var rectWidth=bestRange.toRect(ctx.renderCtx).width;
		
		Optional<String> str     =field.instanceToString(ioPool, instance, false);
		Optional<String> shortStr=Optional.empty();
		String           both;
		
		var fStr=field.toShortString();
		if(field instanceof IOField.Ref refField){
			//noinspection unchecked
			var ref=refField.getReference(instance);
			if(ref!=null&&!ref.isNull()) fStr+=" @ "+ref;
		}
		
		var renderCtx=ctx.renderCtx;
		both=fStr+(str.isEmpty()?"":": "+str.get());
		if(str.isPresent()&&getStringBounds(renderCtx.renderer, both).width()>rectWidth){
			shortStr=field.instanceToString(ioPool, instance, true);
			both=fStr+(shortStr.isEmpty()?"":": "+shortStr.get());
		}
		
		if(hover!=null){
			ctx.renderCtx.hoverMessages.add(new HoverMessage(UtilL.stream(ranges).toList(), color, new Object[]{field+": ", new FieldVal<>(ioPool, instance, field)}));
		}
		
		
		if(str.isPresent()&&getStringBounds(renderCtx.renderer, both).width()>rectWidth){
			var font=renderCtx.renderer.getFontScale();
			initFont(renderCtx, 0.4F);
			var rect=bestRange.toRect(ctx.renderCtx);
			rect.y+=renderCtx.renderer.getFontScale()*-0.8;
			drawStringInInfo(renderCtx.renderer, color, fStr, rect, false, ctx.strings);
			renderCtx.renderer.setFontScale(font);
			
			var drawStr=str;
			
			if(getStringBounds(renderCtx.renderer, drawStr.get()).width()>rectWidth){
				if(shortStr.isEmpty()) shortStr=field.instanceToString(ioPool, instance, true);
				drawStr=shortStr;
			}
			var r=bestRange.toRect(ctx.renderCtx);
			r.y+=ctx.renderCtx.pixelsPerByte*0.1F;
			
			drawStr.ifPresent(s->drawStringInInfo(renderCtx.renderer, color, s, r, false, ctx.strings, ctx.stringOutlines));
		}else{
			initFont(renderCtx, 1);
			drawStringInInfo(renderCtx.renderer, color, both, bestRange.toRect(ctx.renderCtx), false, ctx.strings, ctx.stringOutlines);
		}
	}
	
	private void drawLine(RenderContext ctx, long from, long to){
		long xPosFrom=from%ctx.width(), yPosFrom=from/ctx.width();
		long xPosTo  =to%ctx.width(), yPosTo=to/ctx.width();
		
		DrawUtils.drawPixelLine(ctx, xPosFrom+0.5, yPosFrom+0.5, xPosTo+0.5, yPosTo+0.5);
	}
	
	private Color chunkBaseColor(){
		return Color.GRAY;
	}
	
	private void fillChunk(RenderContext ctx, Chunk chunk, boolean withChar, boolean force){
		var chunkColor=chunkBaseColor();
		var dataColor =alpha(ColorUtils.mul(chunkColor, 0.5F), 0.7F);
		var freeColor =alpha(chunkColor, 0.4F);
		
		int start=(int)chunk.dataStart();
		int cap  =(int)chunk.getCapacity();
		int siz  =(int)chunk.getSize();
		drawByteRanges(ctx, List.of(new Range(start+siz, start+cap)), freeColor, withChar, force);
		drawByteRanges(ctx, List.of(new DrawUtils.Range(start, start+siz)), dataColor, withChar, force);
	}
	
	private void fillBit(RenderContext ctx, float x, float y, int index, float xOff, float yOff){
		int   xi =index%3;
		int   yi =index/3;
		float pxS=ctx.pixelsPerByte()/3F;
		
		float x1=xi*pxS;
		float y1=yi*pxS;
		float x2=(xi+1)*pxS;
		float y2=(yi+1)*pxS;
		
		ctx.renderer.fillQuad(x+xOff+x1, y+yOff+y1, x2-x1, y2-y1);
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
		var info=drawStringInInfo(renderer, color, s, area, alignLeft);
		if(info==null) return;
		
		renderer.getFont().fillStrings(info);
		if(doStroke){
			renderer.getFont().outlineStrings(new DrawFont.StringDraw(info.pixelHeight(), info.xScale(), new Color(0, 0, 0, 0.5F), info.string(), info.x(), info.y()));
		}
	}
	
	private void drawStringInInfo(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean alignLeft, List<DrawFont.StringDraw> strings, List<DrawFont.StringDraw> stringStrokes){
		var info=drawStringInInfo(renderer, color, s, area, alignLeft);
		if(info!=null){
			strings.add(info);
			stringStrokes.add(new DrawFont.StringDraw(info.pixelHeight(), info.xScale(), new Color(0, 0, 0, 0.5F), info.string(), info.x(), info.y()));
		}
	}
	private void drawStringInInfo(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean alignLeft, List<DrawFont.StringDraw> strings){
		var info=drawStringInInfo(renderer, color, s, area, alignLeft);
		if(info!=null) strings.add(info);
	}
	private DrawFont.StringDraw drawStringInInfo(RenderBackend renderer, Color color, String s, DrawUtils.Rect area, boolean alignLeft){
		if(s.isEmpty()) return null;
		
		float fontScale=renderer.getFontScale();
		try{
			if(area.height<fontScale){
				renderer.setFontScale(area.height);
			}
			
			float w, h;
			{
				var rect=getStringBounds(renderer, s);
				
				w=rect.width();
				h=rect.height();
				
				if(w>0){
					double scale=(area.width-1)/w;
					if(scale<0.5){
						float div=scale<0.25?3:2;
						renderer.setFontScale(renderer.getFontScale()/div);
						w=rect.width()/div;
						h=rect.height()/div;
					}
					DrawFont.Bounds sbDots=null;
					while((area.width-1)/w<0.5){
						if(s.isEmpty()) return null;
						if(s.length()==1){
							break;
						}
						s=s.substring(0, s.length()-1).trim();
						rect=getStringBounds(renderer, s);
						if(sbDots==null){
							sbDots=getStringBounds(renderer, "...");
						}
						w=rect.width()+sbDots.width();
						h=Math.max(rect.height(), sbDots.height());
					}
				}
			}
			
			float x=area.x;
			float y=area.y;
			
			x+=alignLeft?0:Math.max(0, area.width-w)/2D;
			y+=h+(area.height-h)/2;
			
			float xScale=1;
			if(w>0){
				double scale=(area.width-1)/w;
				if(scale<1){
					xScale=(float)scale;
				}
			}
			
			return new DrawFont.StringDraw(renderer.getFontScale(), xScale, color, s, x, y);
		}finally{
			renderer.setFontScale(fontScale);
		}
	}
	
	private float calcSize(CachedFrame frame){
		return calcSize(direct.getDisplay(), frame.memData().bytes().length, true);
	}
	private float calcSize(RenderBackend.DisplayInterface displayInterface, int bytesCount, boolean restart){
		var screenHeight=displayInterface.getHeight();
		var screenWidth =displayInterface.getWidth();
		
		int columns;
		if(restart) columns=1;
		else{
			columns=(int)(screenWidth/getPixelsPerByte()+0.0001F);
		}
		
		while(true){
			float newPixelsPerByte=(screenWidth-0.5F)/columns;
			
			float width         =screenWidth/newPixelsPerByte;
			int   rows          =(int)Math.ceil(bytesCount/width);
			int   requiredHeight=(int)Math.ceil(rows*newPixelsPerByte);
			
			if(screenHeight<requiredHeight){
				columns++;
			}else{
				break;
			}
		}
		
		return (screenWidth-0.5F)/columns;
	}
	
	private void handleError(Throwable e, ParsedFrame parsed){
		if(errorMode){
			if(parsed.displayError==null) parsed.displayError=e;
			switch(errorLogLevel){
				case NAME -> warn("{}", e);
				case STACK -> e.printStackTrace();
				case NAMED_STACK -> new RuntimeException("Failed to process frame "+getFramePos(), e).printStackTrace();
			}
		}else throw UtilL.uncheckedThrow(e);
	}
	
	@Override
	public List<HoverMessage> render(){
		frameTimer.start();
		
		renderCount++;
		try{
			errorMode=false;
			return render(getFramePos());
		}catch(Throwable e){
			errorMode=true;
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
		
		
		var d=ctx.renderer.getDisplay();
		if(zoom>1.0001){
			markDirty();
		}
		var zoom=this.zoom*this.zoom;
		
		var w   =d.getWidth();
		var h   =d.getHeight();
		var offX=(w-w*zoom)*(d.getMouseX()/(float)w);
		var offY=(h-h*zoom)*(d.getMouseY()/(float)h);
		
		ctx.renderer.translate(--offX, offY);
		
		drawBackgroundDots(ctx.renderer);
	}
	
	private List<HoverMessage> render(int frameIndex){
		if(getFrameCount()==0){
			renderNoData(direct);
			renderStatic=false;
			return List.of();
		}
		
		var zoom=this.zoom*this.zoom;
		
		if(errorMode) markDirty();
		
		CachedFrame cFrame=getFrame(frameIndex);
		MemFrame    frame =cFrame.memData();
		var         bytes =frame.bytes();
		
		if(bytes.length==0){
			renderNoData(direct);
			renderStatic=false;
			return List.of();
		}
		
		var dis=direct.getDisplay();
		pixelsPerByteChange(calcSize(dis, bytes.length, false));
		
		ParsedFrame parsed=cFrame.parsed();
		if(!errorMode){
			parsed.displayError=null;
		}
		if(!renderStatic){
			var ctx=new RenderContext(null, bytes, getPixelsPerByte(), zoom, dis, null);
			if(lastHoverMessages.stream().anyMatch(m->m.ranges().stream().noneMatch(ctx::isRangeHovered))){
				markDirty();
			}
		}
		
		if(renderStatic||RenderBackend.DRAW_DEBUG){
			renderStatic=false;
			buff.clear();
			
			var rCtx=new RenderContext(RenderBackend.DRAW_DEBUG?direct:buff, bytes, getPixelsPerByte(), zoom, dis, new ArrayList<>());
			
			findHoverChunk(rCtx, parsed, DataProvider.newVerySimpleProvider(MemoryData.builder().withRaw(bytes).asReadOnly().build()));
			
			drawStatic(frame, rCtx, parsed);
			
			if(parsed.lastHoverChunk!=null){
				Random r=new Random(parsed.lastHoverChunk.getPtr().getValue());
				rCtx.hoverMessages.add(new HoverMessage(
					List.of(new Range(parsed.lastHoverChunk.getPtr().getValue(), parsed.lastHoverChunk.dataEnd())),
					new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), 100),
					new Object[]{parsed.lastHoverChunk}
				));
			}
			this.lastHoverMessages=List.copyOf(rCtx.hoverMessages);
		}
		
		var ctx=new RenderContext(direct, bytes, getPixelsPerByte(), zoom, dis, new ArrayList<>(lastHoverMessages));
		
		buff.draw();
		
		findHoverChunk(ctx, parsed, DataProvider.newVerySimpleProvider(MemoryData.builder().withRaw(bytes).asReadOnly().build()));
		
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
		var str="No data!";
		
		int w=renderer.getDisplay().getWidth(), h=renderer.getDisplay().getHeight();
		renderer.setFontScale(Math.min(h, w/(str.length()*0.8F)));
		drawStringIn(renderer, Color.LIGHT_GRAY, str, new DrawUtils.Rect(0, 0, w, h), true);
	}
	@Override
	public void markDirty(){
		renderStatic=true;
	}
	@Override
	public boolean isDirty(){
		return renderStatic;
	}
	private void drawStatic(MemFrame frame, RenderContext ctx, ParsedFrame parsed){
		int frameIndex=parsed.index;
		startFrame(ctx);
		
		byte[] bytes=ctx.bytes;
		var    magic=MagicID.get();
		
		var hasMagic=bytes.length>=magic.limit()&&magic.mismatch(ByteBuffer.wrap(bytes).limit(magic.limit()))==-1;
		if(!hasMagic&&!errorMode){
			throw new RuntimeException("No magic bytes");
		}
		
		if(hasMagic){
			drawByteRanges(ctx, List.of(new Range(0, magic.limit())), Color.BLUE, false, true);
		}else{
			IntPredicate isValidMagicByte=i->bytes.length>i&&magic.get(i)==bytes[i];
			drawBytes(ctx, IntStream.range(0, magic.limit()).filter(isValidMagicByte), Color.BLUE, true, true);
			drawBytes(ctx, IntStream.range(0, magic.limit()).filter(isValidMagicByte.negate()), Color.RED, true, true);
		}
		ctx.renderer.setLineWidth(2F);
		outlineByteRange(Color.WHITE, ctx, new Range(0, magic.limit()));
		drawStringIn(ctx.renderer, Color.WHITE, new String(bytes, 0, Math.min(bytes.length, magic.limit())), new DrawUtils.Rect(0, 0, ctx.pixelsPerByte()*Math.min(magic.limit(), ctx.width()), ctx.pixelsPerByte()), false);
		
		ctx.renderer.setColor(alpha(Color.WHITE, 0.5F));
		
		List<Pointer>     ptrs         =new ArrayList<>();
		Consumer<Pointer> pointerRecord=!TRUE()?p->{}:ptrs::add;
		
		ChunkSet referenced=new ChunkSet();
		try{
			Cluster cluster=parsed.getCluster().orElseGet(()->{
				try{
					var c=new Cluster(MemoryData.builder().withRaw(bytes).asReadOnly().build());
					trace("parsed cluster at frame {}", frameIndex);
					parsed.cluster=new WeakReference<>(c);
					return c;
				}catch(Exception e){
					handleError(new RuntimeException("failed to read cluster on frame "+frameIndex, e), parsed);
				}
				return null;
			});
			if(cluster!=null){
				DataProvider provider=cluster;
				var          cl      =cluster;
				var          root    =cluster.rootWalker(null, false).getRoot();
				
				
				List<DrawFont.StringDraw> strings, stringOutlines;
				if(RenderBackend.DRAW_DEBUG){
					strings=new ArrayList<>(){
						@Override
						public boolean add(DrawFont.StringDraw stringDraw){
							ctx.renderer.getFont().fillStrings(List.of(stringDraw));
							return true;
						}
					};
					stringOutlines=new ArrayList<>(){
						@Override
						public boolean add(DrawFont.StringDraw stringDraw){
							ctx.renderer.getFont().outlineStrings(List.of(stringDraw));
							return true;
						}
					};
				}else{
					strings=new ArrayList<>();
					stringOutlines=new ArrayList<>();
				}
				
				Throwable e1    =null;
				var       annCtx=new AnnotateCtx(ctx, provider, new LinkedList<>(), pointerRecord, strings, stringOutlines);
				
				try{
					try{
						cluster.rootWalker(MemoryWalker.PointerRecord.of(ref->{
							if(!ref.isNull()){
								try{
									for(Chunk chunk : new ChainWalker(ref.getPtr().dereference(cl))){
										referenced.add(chunk.getPtr());
									}
								}catch(IOException e){
									throw UtilL.uncheckedThrow(e);
								}
							}
						}), true).walk();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
					
					annotateStruct(annCtx, root,
					               cluster.getFirstChunk().getPtr().makeReference(),
					               FixedContiguousStructPipe.of(root.getThisStruct()),
					               null, true);
					
				}catch(Throwable e){
					e1=e;
				}
				try{
					assert annCtx.stack.isEmpty();
					annCtx.stack.add(null);
					
					var  done =new ChunkSet();
					var  frees=new ChunkSet(provider.getMemoryManager().getFreeChunks());
					long pos  =cluster.getFirstChunk().getPtr().getValue();
					while(pos<bytes.length){
						try{
							for(Chunk chunk : new PhysicalChunkWalker(cluster.getChunk(ChunkPointer.of(pos)))){
								pos=chunk.dataEnd();
								if(referenced.contains(chunk.getPtr())){
									if(frees.contains(chunk.getPtr())){
										drawByteRanges(ctx,
										               List.of(new Range(chunk.dataStart(), Math.min(cluster.getSource().getIOSize(), chunk.dataEnd()))),
										               alpha(Color.YELLOW, 0.15F), false, false);
									}else{
										fillChunk(ctx, chunk, true, false);
									}
								}else{
									drawByteRanges(ctx,
									               List.of(new Range(chunk.dataStart(), Math.min(cluster.getSource().getIOSize(), chunk.dataEnd()))),
									               alpha(Color.BLUE, 0.1F), false, false);
								}
								if(done.add(chunk.getPtr())){
									chunk.streamNext().map(Chunk::getPtr).forEach(done::add);
									annotateChunk(annCtx, chunk);
								}
							}
						}catch(IOException e){
							var p=(int)pos;
							drawBytes(ctx, IntStream.of(p), Color.RED, true, true);
							pos++;
						}
					}
					annCtx.popStrings(ctx.renderer);
				}catch(Throwable e){
					if(e1!=null) e1.addSuppressed(e);
					else e1=e;
				}
				
				if(e1!=null) throw e1;
			}else{
				var         provider=DataProvider.newVerySimpleProvider(MemoryData.builder().withRaw(bytes).build());
				AnnotateCtx annCtx  =new AnnotateCtx(ctx, provider, new LinkedList<>(), pointerRecord, new ArrayList<>(), new ArrayList<>());
				annCtx.stack.add(null);
				long pos;
				try{
					pos=provider.getFirstChunk().getPtr().getValue();
				}catch(Throwable e){
					pos=magic.limit();
				}
				while(pos<bytes.length){
					try{
						for(Chunk chunk : new PhysicalChunkWalker(provider.getChunk(ChunkPointer.of(pos)))){
							pos=chunk.dataEnd();
							annotateChunk(annCtx, chunk);
						}
					}catch(IOException e){
						var p=(int)pos;
						drawBytes(ctx, IntStream.of(p), Color.RED, true, true);
						pos++;
					}
				}
				annCtx.popStrings(ctx.renderer);
			}
		}catch(Throwable e){
			handleError(e, parsed);
		}
		
		drawBytes(ctx, IntStream.range(0, bytes.length).filter(((IntPredicate)ctx.filled::contains).negate()), alpha(Color.GRAY, 0.5F), true, true);
		
		drawWriteIndex(frame, ctx);
		
		drawPointers(ctx, parsed, ptrs);
		
		drawTimeline(ctx.renderer, frameIndex);
	}
	
	private void annotateChunk(AnnotateCtx ctx, Chunk chunk) throws IOException{
		annotateStruct(ctx, chunk, null, Chunk.PIPE, null, true);
		var rctx=ctx.renderCtx;
		if(chunk.dataEnd()>rctx.bytes.length){
			drawByteRanges(rctx, List.of(new Range(rctx.bytes.length, chunk.dataEnd())), new Color(0, 0, 0, 0.2F), false, false);
		}
	}
	
	private void drawBytes(RenderContext ctx, IntStream stream, Color color, boolean withChar, boolean force){
		drawByteRanges(ctx, Range.fromInts(stream), color, withChar, force);
	}
	
	private void drawByteRanges(RenderContext ctx, List<Range> ranges, Color color, boolean withChar, boolean force){
		List<Range> actualRanges;
		if(force) actualRanges=ranges;
		else actualRanges=Range.filterRanges(ranges, i->!ctx.filled.contains((int)i));
		
		drawByteRangesForced(ctx, actualRanges, color, withChar);
	}
	
	private void drawByteRangesForced(RenderContext ctx, List<Range> ranges, Color color, boolean withChar){
		var col       =color;
		var bitColor  =col;
		var background=ColorUtils.mul(col, 0.5F);
		
		Consumer<Stream<Range>> drawIndex=r->{
			try(var ignored=ctx.renderer.bulkDraw(DrawMode.QUADS)){
				r.forEach(range->DrawUtils.fillByteRange(ctx, range));
			}
		};
		
		List<Range> clampedOverflow=Range.clamp(ranges, ctx.bytes.length);
		
		Supplier<IntStream> clampedInts=()->Range.toInts(clampedOverflow);
		
		
		ctx.renderer.setColor(background);
		try(var ignored=ctx.renderer.bulkDraw(DrawMode.QUADS)){
			for(Range range : clampedOverflow){
				DrawUtils.fillByteRange(ctx, range);
			}
		}
		
		drawIndex.accept(clampedOverflow.stream());
		
		ctx.renderer.setColor(alpha(Color.RED, color.getAlpha()/255F));
		drawIndex.accept(ranges.stream().map(r->{
			if(r.to()<ctx.bytes.length) return null;
			if(r.from()<ctx.bytes.length) return new Range(ctx.bytes.length, r.to());
			return r;
		}).filter(Objects::nonNull));
		
		ctx.renderer.setColor(bitColor);
		try(var ignored=ctx.renderer.bulkDraw(DrawMode.QUADS)){
			clampedInts.get().forEach(i->{
				int b=ctx.bytes[i]&0xFF;
				if(b==0) return;
				int   xi=i%ctx.width(), yi=i/ctx.width();
				float xF=ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
				if(ctx.pixelsPerByte()<6){
					ctx.renderer.fillQuad(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()/8F*Integer.bitCount(b));
				}else{
					for(int bi=0;bi<8;bi++){
						if(((b>>bi)&1)==1){
							fillBit(ctx, xF, yF, bi, 0, 0);
						}
					}
				}
				
			});
		}
		
		if(withChar){
			var c=new Color(1, 1, 1, bitColor.getAlpha()/255F*0.6F);
			
			List<DrawFont.StringDraw> chars=null;
			if(clampedOverflow.size()==1){
				var r=clampedOverflow.get(0);
				if(r.size()==1){
					var i=(int)r.from();
					if(ctx.renderer.getFont().canFontDisplay(ctx.bytes[i])){
						int   xi  =i%ctx.width(), yi=i/ctx.width();
						float xF  =ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
						var   info=drawStringInInfo(ctx.renderer, c, Character.toString((char)(ctx.bytes[i]&0xFF)), new DrawUtils.Rect(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()), false);
						if(info!=null) chars=List.of(info);
					}
				}
			}
			
			if(chars==null){
				chars=clampedInts.get().filter(i->ctx.renderer.getFont().canFontDisplay(ctx.bytes[i])).mapToObj(i->{
					int   xi=i%ctx.width(), yi=i/ctx.width();
					float xF=ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
					
					return drawStringInInfo(ctx.renderer, c, Character.toString((char)(ctx.bytes[i]&0xFF)), new DrawUtils.Rect(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()), false);
				}).filter(Objects::nonNull).toList();
			}
			if(!chars.isEmpty()){
				ctx.renderer.getFont().fillStrings(chars);
			}
		}
		for(Range range : clampedOverflow){
			ctx.filled.add(range.from(), range.to());
		}
	}
	private void drawBackgroundDots(RenderBackend renderer){
		renderer.setColor(errorMode?Color.RED.darker():Color.LIGHT_GRAY);
		
		var screenHeight=renderer.getDisplay().getHeight();
		var screenWidth =renderer.getDisplay().getWidth();
		
		try(var ignored=renderer.bulkDraw(DrawMode.QUADS)){
			float jitter      =4;
			int   step        =15;
			float randX       =renderCount/20f;
			float randY       =renderCount/20f+10000;
			float simplexScale=50;
			for(int x=0;x<screenWidth+2;x+=step){
				for(int y=(x/step)%step;y<screenHeight+2;y+=step){
					float xf=x/simplexScale;
					float yf=y/simplexScale;
					renderer.fillQuad(x+SimplexNoise.noise(xf, yf, randX)*jitter, y+SimplexNoise.noise(xf, yf, randY)*jitter, 1.5, 1.5);
				}
			}
		}
	}
	
	private void drawTimeline(RenderBackend renderer, int frameIndex){
		if(getFrameCount()<2) return;
		
		var screenHeight=renderer.getDisplay().getHeight();
		var screenWidth =renderer.getDisplay().getWidth();
		
		renderer.pushMatrix();
		renderer.translate(0, screenHeight);
		renderer.scale(1, -1);
		
		double w=screenWidth/(double)getFrameCount();
		
		double height=6;
		
		renderer.setColor(Color.BLUE.darker());
		renderer.fillQuad(frameIndex*w, 0, w, height*1.5);
		renderer.fillQuad(frameIndex*w-0.75, 0, 1.5, height*1.5);
		
		renderer.setColor(alpha(Color.WHITE, 0.3F));
		renderer.fillQuad(w, 0, screenWidth, height);
		renderer.popMatrix();
	}
	
	private void drawWriteIndex(MemFrame frame, RenderContext ctx){
		ctx.renderer.setColor(Color.YELLOW);
		for(long id : frame.ids()){
			if(id>=frame.bytes().length) continue;
			int i =(int)id;
			int xi=i%ctx.width();
			int yi=i/ctx.width();
			
			fillBit(ctx, 0, 0, 8, xi*ctx.pixelsPerByte(), yi*ctx.pixelsPerByte());
		}
	}
	
	private void drawPointers(RenderContext ctx, ParsedFrame parsed, List<Pointer> ptrs){
		var                       renderer=ctx.renderer;
		List<DrawFont.StringDraw> strings =new ArrayList<>(ptrs.size());
		for(Pointer ptr : ptrs){
			boolean drawMsg=false;
			
			var siz  =ctx.pixelsPerByte()/16F;
			var alpha=Math.min(1, siz);
			siz=Math.max(1, siz);
			var sFul =siz;
			var sHalf=siz/2;
			renderer.setLineWidth(sFul*ptr.widthFactor());
			
			var start=ptr.from();
			var end  =ptr.to();
			
			int pSiz=ptr.size();
			
			Color col;
			if(parsed.lastHoverChunk!=null&&new ChainWalker(parsed.lastHoverChunk).stream().anyMatch(ch->ch.rangeIntersects(ptr.from()))){
				col=ColorUtils.mix(chunkBaseColor(), ptr.color(), 0.5F);
				drawMsg=true;
				renderer.setLineWidth(sFul*ptr.widthFactor()*2);
				
			}else{
				col=ptr.color();
			}
			
			renderer.setColor(alpha(col, 0.7F*alpha));
			
			if(pSiz>1&&LongStream.range(start, start+pSiz).noneMatch(i->i%ctx.width()==0)){
				renderer.setColor(alpha(col, 0.2F*alpha));
				renderer.setLineWidth(sHalf*ptr.widthFactor());
				drawLine(ctx, start, start+pSiz-1);
				renderer.setLineWidth(sFul*ptr.widthFactor());
				renderer.setColor(alpha(col, 0.7F*alpha));
			}
			
			start+=pSiz/2;
			
			long
				xPosFrom=start%ctx.width(),
				yPosFrom=start/ctx.width(),
				xPosTo=end%ctx.width(),
				yPosTo=end/ctx.width();
			
			var direction=start<end;
			var ySmall   =Math.abs(yPosFrom-yPosTo)>1;
			var dirY     =direction?1:-1;
			var dirX     =0;
			
			double
				offScale=ySmall?Math.abs(yPosFrom-yPosTo)/6D:2,
				xFromOff=(dirX)*offScale,
				yFromOff=(dirY)*offScale,
				xToOff=(-dirX)*offScale,
				yToOff=((ySmall?-1:1)*dirY)*offScale,
				
				xFromOrg=xPosFrom+(pSiz==0?0:0.5),
				yFromOrg=yPosFrom+0.5,
				xToOrg=xPosTo+0.5,
				yToOrg=yPosTo+0.5,
				
				xFrom=xFromOrg+xFromOff,
				yFrom=yFromOrg+yFromOff,
				xTo=xToOrg+xToOff,
				yTo=yToOrg+yToOff;
			
			var screenHeight=renderer.getDisplay().getHeight();
			var screenWidth =renderer.getDisplay().getWidth();
			
			if(xFrom<0||xFrom>screenWidth) xFrom=xFromOrg-xFromOff;
			if(yFrom<0||yFrom>screenHeight) yFrom=yFromOrg-yFromOff;
			if(xTo<0||xTo>screenWidth) xTo=xToOrg-xToOff;
			if(yTo<0||yTo>screenHeight) yTo=yToOrg-yToOff;
			
			DrawUtils.drawPath(ctx, new double[][]{
				{xFromOrg, yFromOrg},
				{xFrom, yFrom},
				{xTo, yTo},
				{xToOrg, yToOrg}
			}, true);
			
			if(drawMsg&&!ptr.message().isEmpty()){
				float
					x=(float)(xFrom+xTo)/2*ctx.pixelsPerByte(),
					y=(float)(yFrom+yTo)/2*ctx.pixelsPerByte();
				initFont(ctx, 0.3F*ptr.widthFactor());
				renderer.setFontScale(Math.max(renderer.getFontScale(), 15));
				int msgWidth=ptr.message().length();
				int space   =(int)(screenWidth-x);
				
				var w=getStringBounds(renderer, ptr.message()).width();
				while(w>space*1.5){
					msgWidth--;
					if(msgWidth==0) break;
					w=getStringBounds(renderer, ptr.message().substring(0, msgWidth)).width();
				}
				List<String> lines=msgWidth==0?List.of(ptr.message()):TextUtil.wrapLongString(ptr.message(), msgWidth);
				y-=renderer.getLineWidth()/2F*lines.size();
				for(String line : lines){
					drawStringInInfo(renderer, col, line, new DrawUtils.Rect(x, y, space, ctx.pixelsPerByte()), true, strings);
					y+=renderer.getLineWidth();
				}
			}
		}
		renderer.getFont().fillStrings(strings);
	}
	
	private void drawError(RenderContext ctx, ParsedFrame parsed){
		if(parsed.displayError==null) return;
		var renderer    =ctx.renderer;
		var screenHeight=renderer.getDisplay().getHeight();

//		parsed.displayError.printStackTrace();
		initFont(ctx, 0.2F);
		renderer.setFontScale(Math.max(renderer.getFontScale(), 12));
		
		
		var msg       =DrawUtils.errorToMessage(parsed.displayError);
		var lines     =msg.split("\n");
		var bounds    =Arrays.stream(lines).map(s->getStringBounds(renderer, s)).toList();
		var totalBound=bounds.stream().reduce((l, r)->new DrawFont.Bounds(Math.max(l.width(), r.width()), l.height()+r.height())).orElseThrow();
		
		renderer.setColor(alpha(Color.RED.darker(), 0.2F));
		renderer.fillQuad(0, screenHeight-totalBound.height()-25, totalBound.width()+20, totalBound.height()+20);
		
		var col =alpha(Color.WHITE, 0.8F);
		var rect=new DrawUtils.Rect(10, screenHeight-totalBound.height()-20, totalBound.width(), renderer.getLineWidth());
		
		List<DrawFont.StringDraw> strings=new ArrayList<>(lines.length);
		
		for(int i=0;i<lines.length;i++){
			String line =lines[i];
			var    bound=bounds.get(i);
			rect.height=bound.height();
			rect.y=(Math.round(screenHeight-totalBound.height()+bounds.stream().limit(i).mapToDouble(DrawFont.Bounds::height).sum())-15);
			drawStringInInfo(renderer, col, line, rect, true, strings);
		}
		renderer.getFont().fillStrings(strings);
	}
	
	private void drawMouse(RenderContext ctx, CachedFrame frame){
		var bytes =frame.memData().bytes();
		var parsed=frame.parsed();
		
		int byteIndex=ctx.hoverByteIndex;
		if(byteIndex==-1) return;
		
		record CRange(Color col, Range rang){}
		
		var              flatRanges=new ArrayList<CRange>();
		Map<Long, Color> colorMap  =new HashMap<>();
		
		for(int i=ctx.hoverMessages.size()-1;i>=0;i--){
			HoverMessage hoverMessage=ctx.hoverMessages.get(i);
			if(hoverMessage.color()==null||hoverMessage.isRangeEmpty()){
				continue;
			}
			hoverMessage.ranges().stream().flatMapToLong(Range::longs).forEach(l->colorMap.put(l, hoverMessage.color()));
		}
		
		colorMap.entrySet().stream().sorted(Comparator.comparingLong(Map.Entry::getKey)).forEach(e->{
			if(flatRanges.isEmpty()){
				flatRanges.add(new CRange(e.getValue(), Range.fromSize(e.getKey(), 1)));
				return;
			}
			var  last=flatRanges.get(flatRanges.size()-1);
			long pos =e.getKey();
			if(last.col.equals(e.getValue())&&pos==last.rang.to()){
				flatRanges.set(flatRanges.size()-1, new CRange(e.getValue(), new Range(last.rang.from(), e.getKey()+1)));
				return;
			}
			
			flatRanges.add(new CRange(e.getValue(), Range.fromSize(e.getKey(), 1)));
		});
		
		if(parsed.lastHoverChunk!=null){
			var chunk=parsed.lastHoverChunk;
			ctx.renderer.setLineWidth(1);
			try{
				outlineChunk(ctx, chunk, ColorUtils.mix(chunkBaseColor(), Color.WHITE, 0.4F));
			}catch(IOException e){
				handleError(e, parsed);
			}
		}
		
		for(CRange flatRange : flatRanges){
			DrawUtils.fillByteRange(alpha(flatRange.col, 0.15F), ctx, flatRange.rang);
		}
		
		for(int i=ctx.hoverMessages.size()-1;i>=0;i--){
			HoverMessage hoverMessage=ctx.hoverMessages.get(i);
			if(hoverMessage.color()==null||hoverMessage.isRangeEmpty()){
				continue;
			}
			ctx.renderer.setLineWidth(i+1);
			for(Range range : hoverMessage.ranges()){
				outlineByteRange(hoverMessage.color(), ctx, range);
			}
		}
		
		var b   =bytes[byteIndex]&0xFF;
		var bStr=b+"";
		while(bStr.length()<3) bStr+=" ";
		bStr=bStr+(ctx.renderer.getFont().canFontDisplay(bytes[byteIndex])?" = "+(char)b:"");
		ctx.hoverMessages().addAll(0, List.of(new HoverMessage(List.of(new Range(0, 0)), null, new Object[]{"@"+byteIndex}), new HoverMessage(List.of(new Range(0, 0)), null, new Object[]{bStr})));
		
		ctx.renderer.setLineWidth(3);
		outlineByteRange(Color.BLACK, ctx, Range.fromSize(byteIndex, 1));
		
		ctx.renderer.setColor(Color.WHITE);
		ctx.renderer.setLineWidth(1);
		outlineByteRange(Color.WHITE, ctx, Range.fromSize(byteIndex, 1));
		
	}
	
	private DrawFont.Bounds getStringBounds(RenderBackend renderer, String s){
		return renderer.getFont().getStringBounds(s, renderer.getFontScale());
	}
	
	private void findHoverChunk(RenderContext ctx, ParsedFrame parsed, DataProvider provider){
		int byteIndex=ctx.hoverByteIndex;
		if(byteIndex==-1){
			if(parsed.lastHoverChunk!=null) markDirty();
			parsed.lastHoverChunk=null;
			return;
		}
		
		try{
			if(parsed.lastHoverChunk==null||!parsed.lastHoverChunk.rangeIntersects(byteIndex)){
				parsed.lastHoverChunk=null;
				for(Chunk chunk : new PhysicalChunkWalker(provider.getFirstChunk())){
					if(chunk.rangeIntersects(byteIndex)){
						markDirty();
						parsed.lastHoverChunk=chunk;
						break;
					}
				}
			}
		}catch(IOException e){
			if(parsed.lastHoverChunk!=null) markDirty();
			parsed.lastHoverChunk=null;
			ctx.hoverMessages.add(new HoverMessage(List.of(new Range(0, 0)), null, new Object[]{"Unable to find hover chunk"}));
		}
	}
	
	private record AnnotateStackFrame(IOInstance<?> instance, Reference ref){}
	
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
	private <T extends IOInstance<T>> void annotateStruct(AnnotateCtx ctx,
	                                                      T instance, Reference instanceReference, StructPipe<T> pipe, GenericContext parentGenerics, boolean annotate) throws IOException{
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=ctx.provider.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return;
		
		var                renderer=ctx.renderCtx.renderer;
		AnnotateStackFrame frame   =new AnnotateStackFrame(instance, reference);
		try{
			if(ctx.stack.contains(frame)) return;
			ctx.stack.add(frame);
			
			var typeHash=instance.getThisStruct().getType().getName().hashCode();
			
			var rctx=ctx.renderCtx;
			
			RuntimeException fieldErr=null;
			Random           rand    =new Random();
			renderer.setLineWidth(4);
			
			var iterator=makeFieldIterator(instance, pipe);
			
			long fieldOffset=0;
			long offsetStart;
			
			if(instance instanceof Chunk){
				offsetStart=reference.getPtr().add(reference.getOffset());
			}else{
				offsetStart=reference.calcGlobalOffset(ctx.provider);
			}
			
			var ioPool=instance.getThisStruct().allocVirtualVarPool(IO);
			while(iterator.hasNext()){
				IOField<T, Object> field=iterator.next();
				try{
					
					var acc=field.getAccessor();
					if(acc instanceof VirtualAccessor<T> vAcc&&vAcc.getStoragePool()==IO){
						try{
							reference.withContext(ctx.provider).io(io->{
								pipe.readSingleField(ioPool, ctx.provider, io, field, instance, generics(instance, parentGenerics));
							});
						}catch(Throwable e){
							drawByteRangesForced(ctx.renderCtx, List.of(Range.fromSize(reference.calcGlobalOffset(ctx.provider), 1)), Color.RED, false);
//							throw new RuntimeException("Failed to read full object data"+reference, e);
						}
					}
					
					var col=ColorUtils.makeCol(rand, typeHash, field);
					
					final long size;
					
					long trueOffset=offsetStart+fieldOffset;
					var  sizeDesc  =field.getSizeDescriptor();
					size=sizeDesc.calcUnknown(ioPool, ctx.provider, instance, sizeDesc.getWordSpace());
					
					try{
						if(acc!=null&&acc.hasAnnotation(IODynamic.class)){
							
							var inst=field.get(ioPool, instance);
							if(inst==null) continue;
							if(SupportedPrimitive.isAny(inst.getClass())||inst.getClass()==String.class){
								if(annotate) annotateByteField(ctx, ioPool, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								continue;
							}
							if(inst instanceof IOInstance<?> ioi){
								annotateStruct(ctx, (T)ioi, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), ioi.getThisStruct()), generics(instance, parentGenerics), annotate);
								continue;
							}
							if(inst instanceof IOInstance<?>[] arr){
								annotateDynamicArrayValueLength(ctx, instance, reference, fieldOffset, ioPool, field, col, arr);
								if(arr.length>0){
									var arrayOffset=fieldOffset+1+NumberSize.bySize(arr.length).bytes;
									var pip        =StructPipe.of(pipe.getClass(), arr[0].getThisStruct());
									var gens       =generics(instance, parentGenerics);
									for(var val : arr){
										annotateStruct(ctx, (T)val, reference.addOffset(arrayOffset), pip, gens, annotate);
										arrayOffset+=pip.calcUnknownSize(ctx.provider, val, WordSpace.BYTE);
									}
								}
								continue;
							}
							if(inst.getClass().isArray()){
								var arr=(Object[])inst;
								annotateDynamicArrayValueLength(ctx, instance, reference, fieldOffset, ioPool, field, col, arr);
								var ahead      =1+NumberSize.bySize(arr.length).bytes;
								var arrayOffset=fieldOffset+ahead;
								if(annotate) annotateByteField(ctx, ioPool, instance, field, col, reference, Range.fromSize(arrayOffset, size-ahead));
								continue;
							}
							warn("unmanaged dynamic type {}", inst);
							continue;
						}
						
						if(field instanceof IOField.Ref refO){
							IOField.Ref<T, ?> refField=(IOField.Ref<T, ?>)refO;
							var               ref     =refField.getReference(instance);
							boolean           diffPos =true;
							Pointer           ptr     =null;
							if(!ref.isNull()){
								var from=reference.addOffset(fieldOffset).calcGlobalOffset(ctx.provider);
								var to  =ref.calcGlobalOffset(ctx.provider);
								diffPos=from!=to;
								if(diffPos){
									ptr=new Pointer(from, to, (int)size, col, refField.toString(), 0.8F);
								}
							}
							try{
								if(annotate){
									annotateByteField(ctx, ioPool, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								}
							}catch(Throwable e){
								if(ptr!=null){
									ptr=new Pointer(ptr.from, ptr.to, ptr.size, Color.RED, ptr.message, 1);
								}
								throw e;
							}finally{
								if(ptr!=null){
									ctx.recordPointer(ptr);
								}
							}
							if(!diffPos){
								int xByte    =(int)(renderer.getDisplay().getMouseX()/rctx.pixelsPerByte());
								int yByte    =(int)(renderer.getDisplay().getMouseY()/rctx.pixelsPerByte());
								int byteIndex=yByte*rctx.width()+xByte;
								
								var from=trueOffset;
								var to  =from+size;
								if(from<=byteIndex&&byteIndex<to){
									diffPos=true;
								}
							}
							if(!diffPos) ctx.popStrings(renderer);
							
							if(refField instanceof IOField.Ref.Inst instRef){
								annotateStruct(ctx, (T)refField.get(ioPool, instance), ref, instRef.getReferencedPipe(instance), generics(instance, parentGenerics), diffPos);
							}else{
								ObjectPipe pip     =refField.getReferencedPipe(instance);
								var        refVal  =refField.get(null, instance);
								var        dataSize=pip.calcUnknownSize(ctx.provider, refVal, WordSpace.BYTE);
								annotateByteField(ctx, ioPool, instance, refField, col, ref, Range.fromSize(0, dataSize));
							}
							
							continue;
						}
						if(field instanceof BitFieldMerger<T> merger){
							int bitOffset=0;
							drawByteRanges(rctx, List.of(Range.fromSize(trueOffset, size)), col, false, true);
							for(IOField.Bit<T, ?> bit : merger.fieldGroup()){
								
								var bCol=ColorUtils.makeCol(rand, typeHash, bit);
								var siz =bit.getSizeDescriptor().calcUnknown(ioPool, ctx.provider, instance, WordSpace.BIT);
								
								if(annotate) annotateBitField(ctx, ioPool, instance, bit, bCol, bitOffset, siz, reference, fieldOffset);
								bitOffset+=siz;
							}
							continue;
						}
						if(acc==null){
							throw new RuntimeException("unknown field "+field);
						}
						
						if(UtilL.instanceOf(acc.getType(), ChunkPointer.class)){
							
							var ch=(ChunkPointer)field.get(ioPool, instance);
							
							if(annotate) annotateByteField(ctx, ioPool, instance, field, col, reference, Range.fromSize(fieldOffset, size));
							
							if(!ch.isNull()){
								var msg=field.toString();
								try{
									ctx.popStrings(renderer);
									annotateStruct(ctx, ch.dereference(ctx.provider), null, Chunk.PIPE, generics(instance, parentGenerics), true);
								}catch(Exception e){
									msg=msg+"\n"+DrawUtils.errorToMessage(e);
									col=Color.RED;
								}
								ctx.recordPointer(new Pointer(trueOffset, ch.getValue(), (int)size, col, msg, 0.8F));
							}
						}else if(SupportedPrimitive.isAny(acc.getType())||Stream.of(INumber.class, Enum.class).anyMatch(c->UtilL.instanceOf(acc.getType(), c))){
							if(annotate){
								renderer.setColor(col);
								if(sizeDesc.getWordSpace()==WordSpace.BIT){
									annotateBitField(ctx, ioPool, instance, field, col, 0, size, reference, fieldOffset);
								}else{
									annotateByteField(ctx, ioPool, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								}
							}
						}else{
							var typ=acc.getType();
							if(typ==Object.class){
								var inst=field.get(ioPool, instance);
								if(inst==null){
									continue;
								}else{
									typ=inst.getClass();
								}
							}
							if(IOInstance.isInstance(typ)){
								var inst=(IOInstance<?>)field.get(ioPool, instance);
								if(inst!=null){
									annotateStruct(ctx, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), generics(instance, parentGenerics), annotate);
								}
								continue;
							}
							boolean isList=typ==List.class||typ==ArrayList.class;
							if(typ.isArray()||isList){
								
								Class<?> comp;
								if(isList){
									var gTyp=acc.getGenericType(parentGenerics);
									comp=switch(gTyp){
										case ParameterizedType p -> Utils.typeToRaw(p.getActualTypeArguments()[0]);
										case Class<?> c -> Object.class;
										default -> throw new NotImplementedException(gTyp.getClass().getName());
									};
								}else{
									comp=typ.componentType();
								}
								
								if(IOInstance.isManaged(comp)){
									Object instTmp=field.get(ioPool, instance);
									if(instTmp==null) continue;
									List<IOInstance<?>> inst=(List<IOInstance<?>>)(isList?instTmp:ArrayViewList.create((Object[])instTmp, null));
									if(inst.isEmpty()) continue;
									
									StructPipe elementPipe=ContiguousStructPipe.of(Struct.ofUnknown(inst.get(0).getClass()));
									long       arrOffset  =0;
									for(IOInstance val : inst){
										annotateStruct(ctx, val, reference.addOffset(fieldOffset+arrOffset), elementPipe, generics(instance, parentGenerics), annotate);
										arrOffset+=elementPipe.getSizeDescriptor().calcUnknown(elementPipe.makeIOPool(), ctx.provider, val, WordSpace.BYTE);
									}
									continue;
								}
								if(comp==float.class){
									var inst  =(float[])field.get(ioPool, instance);
									var arrSiz=inst.length;
									
									if(size==arrSiz*4L){
										long  arrOffset=0;
										int[] index    ={0};
										var f=new IOFieldPrimitive.FFloat<T>(new FieldAccessor<>(){
											@NotNull
											@Override
											public <T1 extends Annotation> Optional<T1> getAnnotation(Class<T1> annotationClass){
												return Optional.empty();
											}
											@Override
											public int getTypeID(){
												return TypeFlag.ID_OBJECT;
											}
											@Override
											public Struct<T> getDeclaringStruct(){
												return instance.getThisStruct();
											}
											@NotNull
											@Override
											public String getName(){
												return "["+index[0]+"]";
											}
											@Override
											public Type getGenericType(GenericContext genericContext){
												return Float.class;
											}
											@Override
											public Object get(VarPool<T> ioPool, T instance){
												return inst[index[0]];
											}
											@Override
											public void set(VarPool<T> ioPool, T instance, Object value){
												throw new UnsupportedOperationException();
											}
										});
										for(int i=0;i<arrSiz;i++){
											index[0]=i;
											annotateByteField(
												ctx, ioPool, instance, f,
												col,
												reference,
												Range.fromSize(fieldOffset+arrOffset, 4));
											arrOffset+=4;
										}
										continue;
									}
								}
								if(comp==String.class){
									var inst  =(String[])field.get(ioPool, instance);
									var arrSiz=inst.length;
									
									long  arrOffset=0;
									int[] index    ={0};
									var f=new IOField.NoIO<T, String>(new FieldAccessor<>(){
										@NotNull
										@Override
										public <T1 extends Annotation> Optional<T1> getAnnotation(Class<T1> annotationClass){
											return Optional.empty();
										}
										@Override
										public Struct<T> getDeclaringStruct(){
											return instance.getThisStruct();
										}
										@Override
										public int getTypeID(){
											return TypeFlag.ID_OBJECT;
										}
										@NotNull
										@Override
										public String getName(){
											return "["+index[0]+"]";
										}
										@Override
										public Type getGenericType(GenericContext genericContext){
											return String.class;
										}
										@Override
										public Object get(VarPool<T> ioPool, T instance){
											return inst[index[0]];
										}
										@Override
										public void set(VarPool<T> ioPool, T instance, Object value){
											throw new UnsupportedOperationException();
										}
									}, null);
									for(int i=0;i<arrSiz;i++){
										index[0]=i;
										var siz=AutoText.PIPE.calcUnknownSize(ctx.provider, new AutoText(inst[i]), WordSpace.BYTE);
										annotateByteField(
											ctx, ioPool, instance, f,
											col,
											reference,
											Range.fromSize(fieldOffset+arrOffset, siz));
										arrOffset+=siz;
									}
									continue;
								}
							}
							if(typ==String.class){
								if(annotate) annotateByteField(ctx, ioPool, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								continue;
							}
							warn("unmanaged draw type: {} accessor: {}", typ, acc);
						}
					}finally{
						fieldOffset+=sizeDesc.mapSize(WordSpace.BYTE, size);
					}
				}catch(Throwable e){
					String instStr=instanceErrStr(instance);
					var    err    =new RuntimeException("failed to annotate "+field+" at "+reference.addOffset(fieldOffset)+" in "+instStr+" at "+reference, e);
					
					if(fieldErr==null){
						fieldErr=err;
					}else{
						fieldErr.addSuppressed(err);
					}
					var sizeDesc=field.getSizeDescriptor();
					var size    =sizeDesc.calcUnknown(ioPool, ctx.provider, instance, WordSpace.BYTE);
					outlineByteRange(Color.RED, ctx.renderCtx, Range.fromSize(offsetStart+fieldOffset-size, size));
				}
			}
			if(fieldErr!=null){
				throw fieldErr;
			}
			
			var ranges=instance instanceof Chunk?List.of(Range.fromSize(offsetStart, fieldOffset)):DrawUtils.chainRangeResolve(ctx.provider, reference, 0, fieldOffset);
			for(Range range : ranges){
				if(rctx.isRangeHovered(range)){
					rctx.hoverMessages.add(new HoverMessage(UtilL.stream(ranges).toList(), ColorUtils.makeCol(rand, typeHash, offsetStart+""), new Object[]{"Inst: ", instance}));
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
	private <T extends IOInstance<T>> void annotateDynamicArrayValueLength(AnnotateCtx ctx, T instance, Reference reference, long fieldOffset, VarPool<T> ioPool, IOField<T, Object> field, Color col, Object[] arr){
		var arrayLenSiz=NumberSize.bySize(arr.length);
		
		var arrayLenName    =IOFieldTools.makeCollectionLenName(field.getAccessor());
		var arrayLenSizeName=IOFieldTools.makeNumberSizeName(arrayLenName);
		
		annotateByteField(ctx, ioPool, instance, new IOField.NoIO<>(new AbstractFieldAccessor<T>(null, arrayLenSizeName){
			@NotNull
			@Override
			public <T1 extends Annotation> Optional<T1> getAnnotation(Class<T1> annotationClass){
				return Optional.empty();
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return NumberSize.class;
			}
			@Override
			public int getTypeID(){
				return TypeFlag.ID_OBJECT;
			}
			@Override
			public Object get(VarPool<T> ioPool, T instance){
				return arrayLenSiz;
			}
			@Override
			public void set(VarPool<T> ioPool, T instance, Object value){
				throw new UnsupportedOperationException();
			}
		}, SizeDescriptor.Fixed.of(1)), col, reference, Range.fromSize(fieldOffset, 1));
		
		annotateByteField(ctx, ioPool, instance, new IOField.NoIO<>(new AbstractFieldAccessor<T>(null, arrayLenName){
			@NotNull
			@Override
			public <T1 extends Annotation> Optional<T1> getAnnotation(Class<T1> annotationClass){
				return Optional.empty();
			}
			@Override
			public Type getGenericType(GenericContext genericContext){
				return int.class;
			}
			@Override
			public int getTypeID(){
				return TypeFlag.ID_OBJECT;
			}
			@Override
			public Object get(VarPool<T> ioPool, T instance){
				return arr.length;
			}
			@Override
			public void set(VarPool<T> ioPool, T instance, Object value){
				throw new UnsupportedOperationException();
			}
		}, SizeDescriptor.Fixed.of(arrayLenSiz.bytes)), col, reference, Range.fromSize(fieldOffset+1, arrayLenSiz.bytes));
	}
	private <T extends IOInstance<T>> GenericContext generics(T instance, GenericContext parentGenerics){
		return instance instanceof IOInstance.Unmanaged<?> u?u.getGenerics():null;
	}
	
	private <T extends IOInstance<T>> String instanceErrStr(T instance){
		String instStr;
		try{
			instStr=instance.toString();
		}catch(Throwable e1){
			instStr="<err toString "+e1.getMessage()+" for "+instance.getClass().getName()+">";
		}
		return instStr;
	}
	
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> Iterator<IOField<T, Object>> makeFieldIterator(T instance, StructPipe<T> pipe){
		var fields=pipe.getSpecificFields();
		if(instance instanceof IOInstance.Unmanaged unmanaged){
			return Stream.concat(fields.stream(), unmanaged.listUnmanagedFields()).iterator();
		}else{
			return (Iterator<IOField<T, Object>>)(Object)fields.iterator();
		}
	}
	
	@Override
	public Optional<SessionHost.HostedSession> getDisplayedSession(){
		return displayedSession;
	}
	@Override
	public void setDisplayedSession(Optional<SessionHost.HostedSession> displayedSession){
		this.displayedSession=displayedSession;
		markDirty();
	}
}
