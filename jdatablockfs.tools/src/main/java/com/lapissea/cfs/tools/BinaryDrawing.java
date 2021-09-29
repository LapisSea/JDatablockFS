package com.lapissea.cfs.tools;


import com.lapissea.cfs.chunk.*;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.LogUtil;
import com.lapissea.util.MathUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import org.joml.SimplexNoise;

import java.awt.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@SuppressWarnings({"UnnecessaryLocalVariable", "SameParameterValue"})
public abstract class BinaryDrawing{
	
	public enum DrawMode{
		QUADS
	}
	
	protected abstract class BulkDraw implements AutoCloseable{
		
		private final boolean val;
		
		public BulkDraw(DrawMode mode){
			start(mode);
			val=bulkDrawing;
			bulkDrawing=true;
		}
		
		@Override
		public void close(){
			bulkDrawing=val;
			end();
		}
		
		protected abstract void start(DrawMode mode);
		protected abstract void end();
	}
	
	private interface DrawB{
		void draw(int index, Color color, boolean withChar, boolean force);
	}
	
	static record RenderContext(int width, int pixelsPerByte){}
	
	protected static record Range(long from, long to){
		static Range fromSize(long start, long size){
			return new Range(start, start+size);
		}
		long size(){
			return to-from;
		}
		Rectangle toRect(RenderContext ctx){
			var xByteFrom=(from%ctx.width)*ctx.pixelsPerByte;
			var yByteFrom=(from/ctx.width)*ctx.pixelsPerByte;
			var xByteTo  =xByteFrom+ctx.pixelsPerByte*size();
			var yByteTo  =yByteFrom+ctx.pixelsPerByte;
			return new Rectangle((int)xByteFrom, (int)yByteFrom, (int)(xByteTo-xByteFrom), (int)(yByteTo-yByteFrom));
		}
	}
	
	private static record Pointer(int from, int to, int size, Color color, String message, float widthFactor){}
	
	protected static final class ParsedFrame{
		final int index;
		WeakReference<Cluster> cluster=new WeakReference<>(null);
		Throwable              displayError;
		Chunk                  lastHoverChunk;
		public ParsedFrame(int index){
			this.index=index;
		}
		
		public Optional<Cluster> getCluster(){
			return Optional.ofNullable(cluster.get());
		}
	}
	
	protected record CachedFrame(MemFrame data, ParsedFrame parsed){}
	
	private static Color mul(Color color, float mul){
		return new Color(Math.round(color.getRed()*mul), Math.round(color.getGreen()*mul), Math.round(color.getBlue()*mul), color.getAlpha());
	}
	
	private static Color add(Color color, Color other){
		return new Color(
			Math.min(255, color.getRed()+other.getRed()),
			Math.min(255, color.getGreen()+other.getGreen()),
			Math.min(255, color.getBlue()+other.getBlue()),
			Math.min(255, color.getAlpha()+other.getAlpha())
		);
	}
	
	private static Color alpha(Color color, float alpha){
		return new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			(int)(alpha*255)
		);
	}
	
	private static Color mix(Color color, Color other, float mul){
		return add(mul(color, 1-mul), mul(other, mul));
	}
	
	private boolean errorMode;
	private boolean bulkDrawing;
	private float   fontScale;
	private float   lineWidth;
	private long    renderCount;
	
	
	protected abstract BulkDraw bulkDraw(DrawMode mode);
	
	protected abstract void fillQuad(double x, double y, double width, double height);
	
	protected abstract void outlineQuad(double x, double y, double width, double height);
	
	protected abstract int getPixelsPerByte();
	
	protected abstract void drawLine(double xFrom, double yFrom, double xTo, double yTo);
	
	protected abstract int getWidth();
	protected abstract int getHeight();
	
	protected abstract int getMouseX();
	protected abstract int getMouseY();
	
	protected abstract void pixelsPerByteChange(int newPixelsPerByte);
	
	protected abstract void setColor(Color color);
	protected abstract void pushMatrix();
	protected abstract void popMatrix();
	
	protected abstract float[] getStringBounds(String str);
	
	protected abstract void translate(double x, double y);
	protected abstract Color readColor();
	protected abstract void initRenderState();
	
	protected abstract void clearFrame();
	
	protected abstract boolean isWritingFilter();
	protected abstract String getFilter();
	
	protected abstract void scale(double x, double y);
	
	protected abstract void rotate(double angle);
	
	protected abstract void outlineString(String str, float x, float y);
	protected abstract void fillString(String str, float x, float y);
	protected abstract boolean canFontDisplay(char c);
	
	protected abstract int getFrameCount();
	protected abstract CachedFrame getFrame(int index);
	
	protected abstract int getFramePos();
	
	protected abstract void preRender();
	protected abstract void postRender();
	
	public boolean isBulkDrawing(){
		return bulkDrawing;
	}
	public float getFontScale(){
		return fontScale;
	}
	public float getLineWidth(){
		return lineWidth;
	}
	private void setStroke(float width){
		lineWidth=width;
	}
	
	
	private void outlineChunk(RenderContext ctx, Chunk chunk, Color color) throws IOException{
		long start=chunk.getPtr().getValue();
		long end  =chunk.dataEnd();
		
		fillByteRange(alpha(color, color.getAlpha()/255F*0.2F), ctx, new Range(start, end));
		setStroke(4);
		outlineByteRange(color, ctx, new Range(start, end));
		var next=chunk.next();
		if(next!=null){
			outlineChunk(ctx, next, alpha(color, color.getAlpha()/255F*0.5F));
		}
	}
	private void fillByteRange(Color color, RenderContext ctx, Range range){
		setColor(color);
		for(var i=range.from();i<range.to();i++){
			long x=i%ctx.width(), y=i/ctx.width();
			fillQuad(x*ctx.pixelsPerByte(), y*ctx.pixelsPerByte(), ctx.pixelsPerByte(), ctx.pixelsPerByte());
		}
	}
	private void outlineByteRange(Color color, RenderContext ctx, Range range){
		setColor(color);
		for(var i=range.from();i<range.to();i++){
			long x =i%ctx.width(), y=i/ctx.width();
			long x1=x, y1=y;
			long x2=x1+1, y2=y1+1;
			
			if(i-range.from()<ctx.width()) drawLine(x1, y1, x2, y1);
			if(range.to()-i<=ctx.width()) drawLine(x1, y2, x2, y2);
			if(x==0||i==range.from()) drawLine(x1, y1, x1, y2);
			if(x2==ctx.width()||i==range.to()-1) drawLine(x2, y1, x2, y2);
		}
	}
	
	private <T extends IOInstance<T>> void annotateBitField(
		ChunkDataProvider provider, RenderContext ctx,
		T instance, IOField<T, ?> field,
		Color col, int bitOffset, long bitSize, Reference reference, long fieldOffset
	) throws IOException{
		Consumer<Rectangle> doSegment=bitRect->{
			setColor(alpha(col, 0.7F));
			drawStringIn(Objects.toString(field.instanceToString(instance, true)), bitRect, false);
			setColor(alpha(col, 0.3F));
			fillQuad(bitRect.x, bitRect.y, bitRect.width, bitRect.height);
		};
		
		if(instance instanceof Chunk ch){
			var trueOffset=ch.getPtr().getValue()+fieldOffset;
			var remaining =bitSize;
			var bitOff    =bitOffset;
			while(remaining>0){
				doSegment.accept(DrawUtils.makeBitRect(ctx, trueOffset, bitOff, remaining));
				remaining-=Math.min(8, remaining);
				bitOff=0;
				trueOffset++;
			}
		}else{
			try(var io=new ChunkChainIO(reference.getPtr().dereference(provider))){
				io.setPos(reference.getOffset()+fieldOffset);
				
				var trueOffset=io.calcGlobalPos();
				var remaining =bitSize;
				var bitOff    =bitOffset;
				while(remaining>0){
					doSegment.accept(DrawUtils.makeBitRect(ctx, trueOffset, bitOff, remaining));
					remaining-=Math.min(8, remaining);
					bitOff=0;
					io.skip(1);
				}
			}
		}
	}
	private <T extends IOInstance<T>> void annotateByteField(
		ChunkDataProvider provider, RenderContext ctx, Consumer<Pointer> pointerRecord,
		T instance, IOField<T, ?> field,
		Color col, Reference reference, Range fieldRange
	){
		Range bestRange=new Range(0, 0);
		Range lastRange=null;
		for(Range range : instance instanceof Chunk ch?
		                  List.of(Range.fromSize(ch.getPtr().getValue()+fieldRange.from(), fieldRange.size())):
		                  FieldWalking.chainRangeResolve(provider, reference, (int)fieldRange.from(), (int)fieldRange.size())){
			if(bestRange.size()<ctx.width()){
				var contiguousRange=DrawUtils.findBestContiguousRange(ctx, range);
				if(bestRange.size()<contiguousRange.size()) bestRange=contiguousRange;
			}
			if(lastRange!=null) pointerRecord.accept(new Pointer((int)lastRange.to()-1, (int)range.from(), 0, col, field.toString(), 0.2F));
			lastRange=range;
			fillByteRange(alpha(col, 0.3F), ctx, range);
		}
		
		setColor(alpha(col, 0.7F));
		drawStringIn(Objects.toString(field.instanceToString(instance, true)), bestRange.toRect(ctx), false);
	}
	
	private void drawArrow(int width, int from, int to){
		int xPosFrom=from%width, yPosFrom=from/width;
		int xPosTo  =to%width, yPosTo=to/width;
		
		double xFrom=xPosFrom+0.5, yFrom=yPosFrom+0.5;
		double xTo  =xPosTo+0.5, yTo=yPosTo+0.5;
		
		double xMid=(xFrom+xTo)/2, yMid=(yFrom+yTo)/2;
		
		double angle=Math.atan2(xTo-xFrom, yTo-yFrom);
		
		double arrowSize=0.4;
		
		double sin=Math.sin(angle)*arrowSize/2;
		double cos=Math.cos(angle)*arrowSize/2;
		
		drawLine(xMid+sin, yMid+cos, xMid-sin-cos, yMid-cos+sin);
		drawLine(xMid+sin, yMid+cos, xMid-sin+cos, yMid-cos-sin);
		drawLine(xFrom, yFrom, xTo, yTo);
	}
	
	private void drawLine(int width, int from, int to){
		int xPosFrom=from%width, yPosFrom=from/width;
		int xPosTo  =to%width, yPosTo=to/width;
		
		drawLine(xPosFrom+0.5, yPosFrom+0.5, xPosTo+0.5, yPosTo+0.5);
	}
	
	private Color chunkBaseColor(Chunk chunk){
		return Color.GREEN;
//		return chunk.isUsed()?chunk.isUserData()?Color.blue.brighter():Color.GREEN:Color.CYAN;
	}
	
	private void fillChunk(DrawB drawByte, Chunk chunk, Function<Color, Color> filter, boolean withChar, boolean force){
		
		var chunkColor=chunkBaseColor(chunk);
		var dataColor =mul(chunkColor, 0.5F);
		var freeColor =alpha(chunkColor, 0.4F);
		
		chunkColor=filter.apply(chunkColor);
		dataColor=filter.apply(dataColor);
		freeColor=filter.apply(freeColor);
		
		for(int i=(int)chunk.getPtr().getValue();i<chunk.dataStart();i++){
			drawByte.draw(i, chunkColor, false, false);
		}
		
		for(int i=0, j=(int)chunk.getCapacity();i<j;i++){
			drawByte.draw((int)(i+chunk.dataStart()), i>=chunk.getSize()?freeColor:dataColor, withChar, force);
		}
	}
	
	private void fillBit(float x, float y, int index, float xOff, float yOff){
		int   xi =index%3;
		int   yi =index/3;
		float pxS=getPixelsPerByte()/3F;
		
		float x1=xi*pxS;
		float y1=yi*pxS;
		float x2=(xi+1)*pxS;
		float y2=(yi+1)*pxS;
		
		fillQuad(x+xOff+x1, y+yOff+y1, x2-x1, y2-y1);
	}
	
	private void initFont(){
		initFont(0.8F);
	}
	
	private void initFont(float sizeMul){
		fontScale=getPixelsPerByte()*sizeMul;
	}
	
	private void drawStringIn(String s, Rectangle area, boolean doStroke){
		drawStringIn(s, area, doStroke, false);
	}
	
	private void drawStringIn(String s, Rectangle area, boolean doStroke, boolean alignLeft){
		var rect=getStringBounds(s);
		
		float w=rect[0];
		float h=rect[1];
		
		float fontScale=this.fontScale;
		
		if(h>0){
			if(area.height<h){
				this.fontScale=area.height;
				
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
		}
		
		if(w>0){
			double scale=(area.width-1)/w;
			if(scale<0.5){
				this.fontScale/=scale<0.25?3:2;
				
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
			while((area.width-1)/w<0.5){
				if(s.isEmpty()) return;
				if(s.length()<=4){
					s=s.charAt(0)+"";
					break;
				}
				s=s.substring(0, s.length()-4)+"...";
				rect=getStringBounds(s);
				
				w=rect[0];
				h=rect[1];
			}
		}
		
		pushMatrix();
		translate(area.x, area.y);
		translate(alignLeft?0:Math.max(0, area.width-w)/2D, h+(area.height-h)/2);
		
		if(w>0){
			double scale=(area.width-1)/w;
			if(scale<1){
				scale(scale, 1);
			}
		}
		
		if(doStroke){
			Color c=readColor();
			
			setColor(new Color(0, 0, 0, 0.5F));
			setStroke(1);
			
			outlineString(s, 0, 0);
			
			setColor(c);
		}
		fillString(s, 0, 0);
		
		this.fontScale=fontScale;
		popMatrix();
	}
	
	protected void calcSize(int bytesCount, boolean restart){
		
		int newPixelsPerByte=MathUtil.snap(restart?500:getPixelsPerByte(), 3, getWidth());
		
		while(true){
			int width         =getWidth()/newPixelsPerByte;
			int rows          =(int)Math.ceil(bytesCount/(double)width);
			int requiredHeight=rows*newPixelsPerByte;
			
			if(this.getHeight()<requiredHeight){
				newPixelsPerByte--;
			}else{
				break;
			}
		}
		
		pixelsPerByteChange(newPixelsPerByte);
	}
	
	private void handleError(Throwable e, ParsedFrame parsed){
		if(errorMode){
			if(parsed.displayError==null) parsed.displayError=e;
//			LogUtil.println(e);
//			e.printStackTrace();
//			new RuntimeException("Failed to process frame "+getFramePos(), e).printStackTrace();
		}else throw UtilL.uncheckedThrow(e);
	}
	
	private void renderByte(int b, RenderContext ctx, boolean withChar, int x, int y){
		int   xF   =ctx.pixelsPerByte()*x, yF=ctx.pixelsPerByte()*y;
		Color color=readColor();
		
		setColor(mul(color, 0.5F));
		fillQuad(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte());
		
		setColor(color);
		
		
		try(var bulkDraw=bulkDraw(DrawMode.QUADS)){
			for(FlagReader flags=new FlagReader(b, 8);flags.remainingCount()>0;){
				try{
					if(flags.readBoolBit()){
						fillBit(xF, yF, flags.readCount()-1, 0, 0);
					}
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		}
		
		
		if(withChar){
			
			char c=(char)((byte)b);
			if(canFontDisplay(c)){
				String s=Character.toString(c);
				setColor(new Color(1, 1, 1, color.getAlpha()/255F*0.6F));
				
				drawStringIn(s, new Rectangle(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()), false);
			}
		}
		setColor(color);
	}
	
	protected void render(){
		renderCount++;
		preRender();
		try{
			errorMode=false;
			render(getFramePos());
		}catch(Throwable e){
			errorMode=true;
			render(getFramePos());
		}
		postRender();
	}
	private void startFrame(){
		clearFrame();
		initRenderState();
		initFont();
		
		drawBackgroundDots();
	}
	private void render(int frameIndex){
		if(getFrameCount()==0){
			startFrame();
			return;
		}
		
		CachedFrame cFrame=getFrame(frameIndex);
		MemFrame    frame =cFrame.data();
		var         bytes =frame.data();
		
		
		var magic=Cluster.getMagicId();
		
		var hasMagic=bytes.length>=magic.limit()&&IntStream.range(0, magic.limit()).allMatch(i->magic.get(i)==bytes[i]);
		if(!hasMagic&&!errorMode){
			throw new RuntimeException("No magic bytes");
		}
		
		BitSet filled=new BitSet(bytes.length);
		
		calcSize(bytes.length, false);
		
		RenderContext ctx=new RenderContext(Math.max(1, this.getWidth()/getPixelsPerByte()), getPixelsPerByte());
		
		DrawB drawByte=(i, color, withChar, force)->{
			if(i<bytes.length){
				if(!force&&filled.get(i)) return;
				filled.set(i);
			}
			
			if(i>=bytes.length) color=alpha(Color.RED, 0.4F);
			
			int b =i>=bytes.length?0xFF:bytes[i]&0xFF;
			int xi=i%ctx.width();
			int yi=i/ctx.width();
			
			setColor(color);
			renderByte(b, ctx, withChar, xi, yi);
		};
		
		startFrame();
		
		setColor(Color.BLUE);
		for(int i=0;i<magic.limit();i++){
			drawByte.draw(i, bytes.length>i&&magic.get(i)==bytes[i]?Color.BLUE:Color.RED, false, true);
		}
		
		setStroke(2F);
		outlineByteRange(Color.WHITE, ctx, new Range(0, magic.limit()));
		setColor(Color.WHITE);
		drawStringIn(new String(bytes, 0, magic.limit()), new Rectangle(0, 0, ctx.pixelsPerByte()*Math.min(magic.limit(), ctx.width()), ctx.pixelsPerByte()), false);
		
		setColor(alpha(Color.WHITE, 0.5F));
		
		List<Pointer>     ptrs    =new ArrayList<>();
		ParsedFrame       parsed  =cFrame.parsed();
		ChunkDataProvider provider=null;
		
		try{
			Cluster cluster=parsed.getCluster().orElseGet(()->{
				try{
					var c=new Cluster(MemoryData.build().withRaw(bytes).asReadOnly().build());
					LogUtil.println("parsed cluster at frame", frameIndex);
					parsed.cluster=new WeakReference<>(c);
					return c;
				}catch(Exception e){
					handleError(new RuntimeException("failed to read cluster on frame "+frameIndex, e), parsed);
				}
				return null;
			});
			if(cluster!=null){
				provider=cluster;
				var        cl        =cluster;
				var        root      =cluster.getRoot();
				Set<Chunk> referenced=new HashSet<>();
				FieldWalking.walkReferences(cluster, new LinkedList<>(), root,
				                            cluster.getFirstChunk().getPtr().makeReference(),
				                            FixedContiguousStructPipe.of(root.getThisStruct()), ref->{
						if(!ref.isNull()){
							try{
								for(Chunk chunk : new ChainWalker(ref.getPtr().dereference(cl))){
									referenced.add(chunk);
								}
							}catch(IOException e){
								throw UtilL.uncheckedThrow(e);
							}
						}
					});
				
				for(Chunk chunk : new PhysicalChunkWalker(cluster.getFirstChunk())){
					if(referenced.contains(chunk)){
						fillChunk(drawByte, chunk, ch->ch, true, true);
					}
					annotateStruct(ctx, cluster, new LinkedList<>(), chunk, null, Chunk.PIPE, ptrs::add);
				}
				
				annotateStruct(ctx, cluster,
				               new LinkedList<>(), root,
				               cluster.getFirstChunk().getPtr().makeReference(),
				               FixedContiguousStructPipe.of(root.getThisStruct()),
				               ptrs::add);
			}else{
				provider=ChunkDataProvider.newVerySimpleProvider(MemoryData.build().withRaw(bytes).build());
				
				for(Chunk chunk : new PhysicalChunkWalker(provider.getFirstChunk())){
					annotateStruct(ctx, provider, new LinkedList<>(), chunk, null, Chunk.PIPE, ptrs::add);
				}
				
			}
		}catch(Throwable e){
			handleError(e, parsed);
		}
		if(provider==null){
			try{
				provider=ChunkDataProvider.newVerySimpleProvider(MemoryData.build().withRaw(bytes).build());
			}catch(IOException e1){
				handleError(e1, parsed);
			}
		}
		
		for(int i=0;i<bytes.length;i++){
			if(filled.get(i)) continue;
			drawByte.draw(i, alpha(Color.GRAY, 0.5F), true, false);
		}
		
		findHoverChunk(ctx, parsed, provider);
		
		drawWriteIndex(frame, ctx);
		for(Pointer ptr : ptrs){
			drawPointer(ctx, parsed, ptr);
		}
		drawMouse(ctx, cFrame);
		drawError(parsed);
		
		drawFilter();
		
		drawTimeline(frameIndex);
	}
	private void drawBackgroundDots(){
		setColor(errorMode?Color.RED.darker():Color.LIGHT_GRAY);
		
		try(var bulkDraw=bulkDraw(DrawMode.QUADS)){
			float jiter       =2;
			int   step        =10;
			float randX       =renderCount/6f;
			float randY       =renderCount/6f+10000;
			float simplexScale=50;
			for(int x=0;x<getWidth()+2;x+=step){
				for(int y=(x/step)%step;y<getHeight()+2;y+=step){
					float xf=x/simplexScale;
					float yf=y/simplexScale;
					fillQuad(x+SimplexNoise.noise(xf, yf, randX)*jiter, y+SimplexNoise.noise(xf, yf, randY)*jiter, 1.5, 1.5);
				}
			}
		}
	}
	
	private void drawTimeline(int frameIndex){
		translate(0, getHeight());
		scale(1, -1);
		
		double w=getWidth()/(double)getFrameCount();
		
		boolean lastMatch=false;
		int     start    =0;
		
		double height=6;
		
		setColor(Color.BLUE.darker());
		fillQuad(frameIndex*w, 0, w, height*1.5);
		fillQuad(frameIndex*w-0.75, 0, 1.5, height*1.5);
		
		for(int i=0;i<getFrameCount();i++){
			boolean match=!getFilter().isEmpty()&&Arrays.stream(getFrame(i).data().e().getStackTrace()).map(Object::toString).anyMatch(l->l.contains(getFilter()));
			if(match==lastMatch){
				continue;
			}
			setColor(alpha(lastMatch?Color.RED.darker():Color.WHITE, frameIndex>=start&&frameIndex<=i?0.6F:0.3F));
			fillQuad(start*w, 0, w*(i-start), height);
			lastMatch=match;
			start=i;
		}
		int i=getFrameCount();
		setColor(alpha(lastMatch?Color.RED.darker():Color.WHITE, frameIndex>=start&&frameIndex<=i?0.6F:0.3F));
		fillQuad(start*w, 0, w*(i-start), height);
	}
	private void drawFilter(){
		if(!isWritingFilter()) return;
		setColor(Color.WHITE);
		initFont(1);
		drawStringIn("Filter: "+getFilter(), new Rectangle(0, 0, getWidth(), getHeight()), true, true);
	}
	
	private void drawWriteIndex(MemFrame frame, RenderContext ctx){
		setColor(Color.YELLOW);
		for(long id : frame.ids()){
			if(id>=frame.data().length) continue;
			int i =(int)id;
			int xi=i%ctx.width();
			int yi=i/ctx.width();
			
			fillBit(0, 0, 8, xi*ctx.pixelsPerByte(), yi*ctx.pixelsPerByte());
		}
	}
	
	private void drawPointer(RenderContext ctx, ParsedFrame parsed, Pointer ptr){
		
		var siz  =Math.max(1, ctx.pixelsPerByte()/8F);
		var sFul =siz;
		var sHalf=siz/2;
		setStroke(sFul*ptr.widthFactor());
		
		int start=ptr.from();
		int end  =ptr.to();
		
		int pSiz=ptr.size();
		
		Color col;
		if(parsed.lastHoverChunk!=null&&new ChainWalker(parsed.lastHoverChunk).stream().anyMatch(ch->ch.rangeIntersects(ptr.from()))){
			col=mix(chunkBaseColor(parsed.lastHoverChunk), ptr.color(), 0.5F);
		}else{
			col=ptr.color();
		}
		
		setColor(alpha(col, 0.5F));
		
		if(pSiz>1&&IntStream.range(start, start+pSiz).noneMatch(i->i%ctx.width()==0)){
			setColor(alpha(col, 0.1F));
			setStroke(sHalf*ptr.widthFactor());
			drawLine(ctx.width(), start, start+pSiz-1);
			setStroke(sFul*ptr.widthFactor());
			setColor(alpha(col, 0.5F));
		}
		
		drawArrow(ctx.width(), start, end);
		
		if(!ptr.message().isEmpty()){
			int xPosFrom=start%ctx.width(), yPosFrom=start/ctx.width();
			int xPosTo  =end%ctx.width(), yPosTo=end/ctx.width();
			
			float xFrom=xPosFrom+0.5F, yFrom=yPosFrom+0.5F;
			float xTo  =xPosTo+0.5F, yTo=yPosTo+0.5F;
			float x    =(xFrom+xTo)/2*ctx.pixelsPerByte(), y=(yFrom+yTo)/2*ctx.pixelsPerByte();
			setColor(col);
			initFont(0.3F*ptr.widthFactor());
			fontScale=Math.max(fontScale, 15);
			int msgWidth=ptr.message().length();
			int space   =(int)(getWidth()-x);
			
			var w=getStringBounds(ptr.message())[0];
			while(w>space*1.5){
				msgWidth--;
				w=getStringBounds(ptr.message().substring(0, msgWidth))[0];
			}
			var lines=TextUtil.wrapLongString(ptr.message(), msgWidth);
			y-=fontScale/2F*lines.size();
			for(String line : lines){
				drawStringIn(line, new Rectangle((int)x, (int)y, space, ctx.pixelsPerByte()), false, true);
				y+=fontScale;
			}
		}
	}
	
	private void drawError(ParsedFrame parsed){
		if(parsed.displayError==null) return;
		
		initFont(0.2F);
		fontScale=Math.max(fontScale, 12);
		
		
		var msg       =DrawUtils.errorToMessage(parsed.displayError);
		var lines     =msg.split("\n");
		var bounds    =Arrays.stream(lines).map(this::getStringBounds).toList();
		var totalBound=bounds.stream().reduce((l, r)->new float[]{Math.max(l[0], r[0]), l[1]+r[1]}).orElseThrow();
		
		setColor(alpha(Color.RED.darker(), 0.2F));
		fillQuad(0, getHeight()-totalBound[1]-25, totalBound[0]+20, totalBound[1]+20);
		setColor(alpha(Color.WHITE, 0.8F));
		
		var rect=new Rectangle(10, Math.round(getHeight()-totalBound[1])-20, Math.round(totalBound[0]), (int)fontScale);
		for(int i=0;i<lines.length;i++){
			String line =lines[i];
			var    bound=bounds.get(i);
			rect.height=(int)bound[1];
			rect.y=(int)(Math.round(getHeight()-totalBound[1]+bounds.stream().limit(i).mapToDouble(b->b[1]).sum())-15);
			drawStringIn(line, rect, false, true);
		}
	}
	
	private void drawMouse(RenderContext ctx, CachedFrame frame){
		var bytes =frame.data().data();
		var parsed=frame.parsed;
		
		int xByte=getMouseX()/ctx.pixelsPerByte();
		if(xByte>=ctx.width()) return;
		int yByte    =getMouseY()/ctx.pixelsPerByte();
		int byteIndex=yByte*ctx.width()+xByte;
		if(byteIndex>=bytes.length) return;
		
		translate(0.5, 0.5);
		
		var    b=bytes[byteIndex]&0xFF;
		String s=b+(b>31?"/"+(char)b:"")+" @"+byteIndex;
		
		setColor(Color.BLACK);
		setStroke(2);
		outlineQuad(xByte*ctx.pixelsPerByte(), yByte*ctx.pixelsPerByte(), ctx.pixelsPerByte(), ctx.pixelsPerByte());
		
		setColor(Color.WHITE);
		setStroke(1);
		outlineQuad(xByte*ctx.pixelsPerByte(), yByte*ctx.pixelsPerByte(), ctx.pixelsPerByte(), ctx.pixelsPerByte());
		
		initFont(0.5F);
		pushMatrix();
		int x=xByte*ctx.pixelsPerByte();
		int y=(int)((yByte-0.1)*ctx.pixelsPerByte());
		
		float[] bounds=getStringBounds(s);
		x=(int)Math.min(Math.max(0, x-bounds[0]/2+ctx.pixelsPerByte()/2F), getWidth()-Math.ceil(bounds[0]));
		y=Math.max(y, (int)Math.ceil(bounds[1]));
		
		setColor(Color.BLACK);
		outlineString(s, x, y);
		
		setColor(Color.WHITE);
		fillString(s, x, y);
		
		popMatrix();
		initFont(1);
		
		if(parsed.lastHoverChunk!=null){
			var chunk=parsed.lastHoverChunk;
			setStroke(1);
			try{
				outlineChunk(ctx, chunk, mix(chunkBaseColor(chunk), Color.WHITE, 0.4F));
			}catch(IOException e){
				handleError(e, parsed);
			}
		}
		
		translate(-0.5, -0.5);
	}
	private void findHoverChunk(RenderContext ctx, ParsedFrame parsed, ChunkDataProvider provider){
		int xByte=getMouseX()/ctx.pixelsPerByte();
		if(xByte>=ctx.width()){
			parsed.lastHoverChunk=null;
			return;
		}
		int yByte    =getMouseY()/ctx.pixelsPerByte();
		int byteIndex=yByte*ctx.width()+xByte;
		
		try{
			if(parsed.lastHoverChunk==null||!parsed.lastHoverChunk.rangeIntersects(byteIndex)){
				parsed.lastHoverChunk=null;
				for(Chunk chunk : new PhysicalChunkWalker(provider.getFirstChunk())){
					if(chunk.rangeIntersects(byteIndex)){
						parsed.lastHoverChunk=chunk;
						break;
					}
				}
			}
		}catch(IOException e){
			handleError(e, parsed);
		}
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> void annotateStruct(RenderContext ctx,
	                                                      ChunkDataProvider cluster, List<IOInstance<?>> stack,
	                                                      T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                      Consumer<Pointer> pointerRecord) throws IOException{
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return;
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);
			
			var typeHash=instance.getThisStruct().getType().getName().hashCode()&0xffffffffL;
			
			Random rand=new Random();
			setStroke(4);
			var fieldOffset=0L;
			
			Iterator<IOField<T, ?>> iterator;
			if(instance instanceof IOInstance.Unmanaged unmanaged){
				iterator=Stream.concat(pipe.getSpecificFields().stream(), unmanaged.listUnmanagedFields()).iterator();
			}else{
				iterator=(Iterator<IOField<T, ?>>)pipe.getSpecificFields().iterator();
			}
			
			while(iterator.hasNext()){
				IOField<T, ?> field=iterator.next();
				rand.setSeed((((long)field.getName().hashCode())<<32)|typeHash);
				
				var col=new Color(
					Color.HSBtoRGB(
						rand.nextFloat(),
						rand.nextFloat()/0.4F+0.6F,
						1F
					)
				);
				
				final long size;
				long       offsetStart;
				
				if(instance instanceof Chunk){
					offsetStart=reference.getPtr().add(reference.getOffset());
				}else{
					offsetStart=reference.calcGlobalOffset(cluster);
				}
				
				long trueOffset=offsetStart+fieldOffset;
				var  sizeDesc  =field.getSizeDescriptor();
				size=sizeDesc.calcUnknown(instance);
				
				try{
					if(field instanceof IOField.Ref<?, ?> refO){
						IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
						var               ref     =refField.getReference(instance);
						if(!ref.isNull()){
							pointerRecord.accept(new Pointer((int)trueOffset, (int)ref.calcGlobalOffset(cluster), (int)size, col, refField.toString(), 1));
						}
						annotateStruct(ctx, cluster, stack, refField.get(instance), refField.getReference(instance), refField.getReferencedPipe(instance), pointerRecord);
					}else if(field instanceof BitFieldMerger<?> merger){
						int bitOffset=0;
						for(IOField.Bit<T, ?> bit : ((BitFieldMerger<T>)merger).getGroup()){
							rand.setSeed((((long)bit.getName().hashCode())<<32)|typeHash);
							
							col=new Color(
								Color.HSBtoRGB(
									rand.nextFloat(),
									rand.nextFloat()/0.4F+0.6F,
									1F
								)
							);
							var siz=bit.getSizeDescriptor().calcUnknown(instance);
							
							annotateBitField(cluster, ctx, instance, bit, col, bitOffset, siz, reference, fieldOffset);
							bitOffset+=siz;
						}
					}else if(UtilL.instanceOf(field.getAccessor().getType(), ChunkPointer.class)){
						
						var ch=(ChunkPointer)field.get(instance);
						
						annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
						
						if(!ch.isNull()){
							var msg=field.toString();
							try{
								annotateStruct(ctx, cluster, stack, ch.dereference(cluster), null, Chunk.PIPE, pointerRecord);
							}catch(Exception e){
								msg=msg+"\n"+DrawUtils.errorToMessage(e);
								col=Color.RED;
							}
							pointerRecord.accept(new Pointer((int)trueOffset, ch.getValueInt(), (int)size, col, msg, 0.8F));
						}
					}else if(IOFieldPrimitive.isPrimitive(field.getAccessor().getType())||Stream.of(INumber.class, Enum.class).anyMatch(c->UtilL.instanceOf(field.getAccessor().getType(), c))){
						setColor(col);
						if(sizeDesc.getWordSpace()==WordSpace.BIT){
							annotateBitField(cluster, ctx, instance, field, col, 0, size, reference, fieldOffset);
						}else{
							annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
						}
					}else{
						var typ=field.getAccessor().getType();
						if(UtilL.instanceOf(typ, IOInstance.class)){
							var inst=(IOInstance<?>)field.get(instance);
							if(inst!=null){
								annotateStruct(ctx, cluster, stack, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), pointerRecord);
							}
							continue;
						}
						if(typ==String.class){
							annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
							continue;
						}
						LogUtil.printlnEr("unamanaged draw type:", typ);
					}
				}finally{
					fieldOffset+=field.getSizeDescriptor().toBytes(size);
				}
			}
		}finally{
			stack.remove(instance);
		}
	}
	
}
