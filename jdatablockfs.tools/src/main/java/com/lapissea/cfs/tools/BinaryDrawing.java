package com.lapissea.cfs.tools;


import com.lapissea.cfs.chunk.*;
import com.lapissea.cfs.exceptions.MalformedPointerException;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOType;
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
import java.lang.reflect.Array;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@SuppressWarnings({"UnnecessaryLocalVariable", "SameParameterValue"})
public abstract class BinaryDrawing{
	
	enum ErrorLogLevel{
		NONE,
		NAME,
		STACK,
		NAMED_STACK
	}
	
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
		void draw(Supplier<IntStream> index, Color color, boolean withChar, boolean force);
	}
	
	static record RenderContext(int width, float pixelsPerByte){}
	
	static class Rect{
		float x, y, width, height;
		
		public Rect(float x, float y, float width, float height){
			this.x=x;
			this.y=y;
			this.width=width;
			this.height=height;
		}
	}
	
	protected static record Range(long from, long to){
		static Range fromSize(long start, long size){
			return new Range(start, start+size);
		}
		long size(){
			return to-from;
		}
		Rect toRect(RenderContext ctx){
			var xByteFrom=(from%ctx.width)*ctx.pixelsPerByte;
			var yByteFrom=(from/ctx.width)*ctx.pixelsPerByte;
			var xByteTo  =xByteFrom+ctx.pixelsPerByte*size();
			var yByteTo  =yByteFrom+ctx.pixelsPerByte;
			return new Rect(xByteFrom, yByteFrom, xByteTo-xByteFrom, yByteTo-yByteFrom);
		}
	}
	
	private static record Pointer(long from, long to, int size, Color color, String message, float widthFactor){}
	
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
	
	private final ErrorLogLevel errorLogLevel=UtilL.sysPropertyByClass(BinaryDrawing.class, "errorLogLevel").map(String::toUpperCase).map(v->{
		try{
			return ErrorLogLevel.valueOf(v);
		}catch(IllegalArgumentException e){
			return ErrorLogLevel.NAMED_STACK;
		}
	}).orElse(ErrorLogLevel.NAMED_STACK);
	
	
	protected abstract BulkDraw bulkDraw(DrawMode mode);
	
	protected abstract void fillQuad(double x, double y, double width, double height);
	
	protected abstract void outlineQuad(double x, double y, double width, double height);
	
	protected abstract float getPixelsPerByte();
	
	protected abstract void drawLine(double xFrom, double yFrom, double xTo, double yTo);
	
	protected abstract int getWidth();
	protected abstract int getHeight();
	
	protected abstract int getMouseX();
	protected abstract int getMouseY();
	
	protected abstract void pixelsPerByteChange(float newPixelsPerByte);
	
	protected abstract void setColor(Color color);
	protected abstract void pushMatrix();
	protected abstract void popMatrix();
	
	protected abstract GLFont.Bounds getStringBounds(String str);
	
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
	private boolean canFontDisplay(int code){
		return canFontDisplay((char)code);
	}
	
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
		try(var ignored=bulkDraw(DrawMode.QUADS)){
			for(var i=range.from();i<range.to();i++){
				long x=i%ctx.width(), y=i/ctx.width();
				fillQuad(x*ctx.pixelsPerByte(), y*ctx.pixelsPerByte(), ctx.pixelsPerByte(), ctx.pixelsPerByte());
			}
		}
	}
	private void outlineByteRange(Color color, RenderContext ctx, Range range){
		setColor(color);
		
		try(var ignored=bulkDraw(DrawMode.QUADS)){
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
	}
	
	private <T extends IOInstance<T>> void annotateBitField(
		ChunkDataProvider provider, RenderContext ctx,
		T instance, IOField<T, ?> field,
		Color col, int bitOffset, long bitSize, Reference reference, long fieldOffset
	) throws IOException{
		Consumer<Rect> doSegment=bitRect->{
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
			if(lastRange!=null) pointerRecord.accept(new Pointer(lastRange.to()-1, range.from(), 0, col, field.toString(), 0.2F));
			lastRange=range;
			fillByteRange(alpha(mul(col, 0.8F), 0.4F), ctx, range);
		}
		
		setColor(alpha(mix(col, Color.WHITE, 0.2F), 0.7F));
		
		var rectWidth=bestRange.toRect(ctx).width;
		
		String str     =field.instanceToString(instance, false);
		String shortStr=null;
		String both;
		
		var fStr=field.toShortString();
		if(field instanceof IOField.Ref refField){
			//noinspection unchecked
			var ref=refField.getReference(instance);
			if(ref!=null&&!ref.isNull()) fStr+=" @ "+ref;
		}
		
		both=fStr+(str==null?"":": "+str);
		if(getStringBounds(both).width()>rectWidth){
			shortStr=field.instanceToString(instance, true);
			both=fStr+(shortStr==null?"":": "+shortStr);
		}
		
		
		if(getStringBounds(both).width()>rectWidth){
			var font=fontScale;
			pushMatrix();
			initFont(0.4F);
			translate(0, fontScale*-0.8);
			drawStringIn(fStr, bestRange.toRect(ctx), false);
			popMatrix();
			fontScale=font;
			
			var drawStr=str;
			
			if(getStringBounds(drawStr).width()>rectWidth){
				if(shortStr==null) shortStr=field.instanceToString(instance, true);
				drawStr=shortStr;
			}
			
			drawStringIn((drawStr==null?"":drawStr), bestRange.toRect(ctx), true);
		}else{
			drawStringIn(both, bestRange.toRect(ctx), true);
		}
		
		
	}
	
	private double[] deBoor(int k, int degree, int i, double x, double[] knots, double[][] ctrlPoints){
		if(k==0){
			i=Math.max(0, Math.min(ctrlPoints.length-1, i));
			return ctrlPoints[i];
		}else{
			double   alpha=(x-knots[i])/(knots[i+degree+1-k]-knots[i]);
			double[] p0   =deBoor(k-1, degree, i-1, x, knots, ctrlPoints);
			double[] p1   =deBoor(k-1, degree, i, x, knots, ctrlPoints);
			double[] p    =new double[2];
			p[0]=p0[0]*(1-alpha)+p1[0]*alpha;
			p[1]=p0[1]*(1-alpha)+p1[1]*alpha;
			return p;
		}
	}
	
	private int WhichInterval(double x, double[] knot, int ti){
		int index=-1;
		
		for(int i=1;i<=ti-1;i++){
			if(x<knot[i]){
				index=i-1;
				break;
			}
		}
		if(x==knot[ti-1]){
			index=ti-1;
		}
		return index;
	}
	
	private double[] DeBoor(int _k, double[] T, double[][] handles, double t){
		int i=WhichInterval(t, T, T.length);
		return deBoor(_k, 3, i, t, T, handles);
	}
	
	private void drawPath(double[][] handles, boolean arrow){
		final int _k=3;
		
		var    tPoints=new double[_k+handles.length+1];
		double d      =1.0/(tPoints.length-1);
		for(int i=0;i<tPoints.length;i++){
			tPoints[i]=i*d;
		}
		
		
		if(handles.length<2) return;
		
		try(var ignored=bulkDraw(DrawMode.QUADS)){
			double[] lastPoint=null;
			double   lastAngle=0;
			double   delta    =1/64.0;
			for(double t=tPoints[2];t<tPoints[5];t+=delta){
				double[] newPoint=DeBoor(_k, tPoints, handles, t);
				draw:
				if(lastPoint!=null){
					var angle=Math.atan2(lastPoint[0]-newPoint[0], lastPoint[1]-newPoint[1]);
					if(angle<0) angle+=Math.PI;
					var angleDiff=Math.abs(angle-lastAngle);
					lastAngle=angle;
					
					var minAngle=0.1;
					
					if(angleDiff<minAngle/2){
						delta=Math.min(1/32D, delta*3/2D);
					}else if(angleDiff>minAngle){
						t-=delta;
						delta/=3/2D;
						continue;
					}
					if(arrow){
						var mid=(tPoints[5]+tPoints[2])/2;
						if(t<mid&&(t+delta)>mid){
							drawArrow(lastPoint[0], lastPoint[1], newPoint[0], newPoint[1]);
							break draw;
						}
					}
					drawLine(lastPoint[0], lastPoint[1], newPoint[0], newPoint[1]);
				}
				lastPoint=newPoint;
			}
		}
	}
	
	private void drawArrow(double xFrom, double yFrom, double xTo, double yTo){
		double xMid=(xFrom+xTo)/2, yMid=(yFrom+yTo)/2;
		
		double angle=Math.atan2(xTo-xFrom, yTo-yFrom);
		
		double arrowSize=0.4;
		
		double sin=Math.sin(angle)*arrowSize/2;
		double cos=Math.cos(angle)*arrowSize/2;
		
		drawLine(xMid+sin, yMid+cos, xMid-sin-cos, yMid-cos+sin);
		drawLine(xMid+sin, yMid+cos, xMid-sin+cos, yMid-cos-sin);
		drawLine(xFrom, yFrom, xTo, yTo);
	}
	
	private void drawLine(int width, long from, long to){
		long xPosFrom=from%width, yPosFrom=from/width;
		long xPosTo  =to%width, yPosTo=to/width;
		
		drawLine(xPosFrom+0.5, yPosFrom+0.5, xPosTo+0.5, yPosTo+0.5);
	}
	
	private Color chunkBaseColor(Chunk chunk){
		return Color.GRAY;
//		return chunk.isUsed()?chunk.isUserData()?Color.blue.brighter():Color.GREEN:Color.CYAN;
	}
	
	private void fillChunk(DrawB drawByte, Chunk chunk, Function<Color, Color> filter, boolean withChar, boolean force){
		
		var chunkColor=chunkBaseColor(chunk);
		var dataColor =mul(chunkColor, 0.5F);
		var freeColor =alpha(chunkColor, 0.4F);
		
		chunkColor=filter.apply(alpha(chunkColor, 0.6F));
		dataColor=filter.apply(dataColor);
		freeColor=filter.apply(freeColor);
		
		drawByte.draw(()->IntStream.range((int)chunk.getPtr().getValue(), (int)chunk.dataStart()), chunkColor, false, false);
		int start=(int)chunk.dataStart();
		int cap  =(int)chunk.getCapacity();
		int siz  =(int)chunk.getSize();
		drawByte.draw(()->IntStream.range(0, cap).filter(i->i>=siz).map(i->i+start), freeColor, withChar, force);
		drawByte.draw(()->IntStream.range(0, cap).filter(i->i<siz).map(i->i+start), dataColor, withChar, force);
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
	
	private void drawStringIn(String s, Rect area, boolean doStroke){
		drawStringIn(s, area, doStroke, false);
	}
	
	private void drawStringIn(String s, Rect area, boolean doStroke, boolean alignLeft){
		var rect=getStringBounds(s);
		
		float w=rect.width();
		float h=rect.height();
		
		float fontScale=this.fontScale;
		
		if(h>0){
			if(area.height<h){
				this.fontScale=area.height;
				
				rect=getStringBounds(s);
				
				w=rect.width();
				h=rect.height();
			}
		}
		
		if(w>0){
			double scale=(area.width-1)/w;
			if(scale<0.5){
				this.fontScale/=scale<0.25?3:2;
				
				rect=getStringBounds(s);
				
				w=rect.width();
				h=rect.height();
			}
			while((area.width-1)/w<0.5){
				if(s.isEmpty()) return;
				if(s.length()<=4){
					s=s.charAt(0)+"";
					break;
				}
				s=s.substring(0, s.length()-4)+"...";
				rect=getStringBounds(s);
				
				w=rect.width();
				h=rect.height();
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
		
		fillString(s, 0, 0);
		if(doStroke){
			Color c=readColor();
			
			setColor(new Color(0, 0, 0, 0.5F));
			setStroke(1);
			
			outlineString(s, 0, 0);
			
			setColor(c);
		}
		
		this.fontScale=fontScale;
		popMatrix();
	}
	
	protected void calcSize(int bytesCount, boolean restart){
		var screenHeight=this.getHeight();
		var screenWidth =this.getWidth();
		
		int columns;
		if(restart) columns=1;
		else{
			columns=(int)(getWidth()/getPixelsPerByte()+0.0001F);
		}
		
		while(true){
			float newPixelsPerByte=(getWidth()-0.5F)/columns;
			
			float width         =screenWidth/newPixelsPerByte;
			int   rows          =(int)Math.ceil(bytesCount/width);
			int   requiredHeight=(int)Math.ceil(rows*newPixelsPerByte);
			
			if(screenHeight<requiredHeight){
				columns++;
			}else{
				break;
			}
		}
		
		pixelsPerByteChange((getWidth()-0.5F)/columns);
	}
	
	private void handleError(Throwable e, ParsedFrame parsed){
		if(errorMode){
			if(parsed.displayError==null) parsed.displayError=e;
			switch(errorLogLevel){
				case NAME -> LogUtil.printlnEr(e);
				case STACK -> e.printStackTrace();
				case NAMED_STACK -> new RuntimeException("Failed to process frame "+getFramePos(), e).printStackTrace();
			}
		}else throw UtilL.uncheckedThrow(e);
	}
	
	protected void render(){
		renderCount++;
		preRender();
		try{
			errorMode=false;
			render(getFramePos());
		}catch(Throwable e){
			errorMode=true;
			try{
				render(getFramePos());
			}catch(Throwable e1){
				LogUtil.println(e1);
//				e1.printStackTrace();
			}
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
		
		RenderContext ctx=new RenderContext((int)Math.max(1, this.getWidth()/getPixelsPerByte()), getPixelsPerByte());
		
		startFrame();
		
		setColor(Color.BLUE);
		IntPredicate isValidMagicByte=i->bytes.length>i&&magic.get(i)==bytes[i];
		drawBytes(bytes, filled, ctx, ()->IntStream.range(0, magic.limit()).filter(isValidMagicByte), Color.BLUE, false, true);
		drawBytes(bytes, filled, ctx, ()->IntStream.range(0, magic.limit()).filter(isValidMagicByte.negate()), Color.RED, false, true);
		
		setStroke(2F);
		outlineByteRange(Color.WHITE, ctx, new Range(0, magic.limit()));
		setColor(Color.WHITE);
		drawStringIn(new String(bytes, 0, magic.limit()), new Rect(0, 0, ctx.pixelsPerByte()*Math.min(magic.limit(), ctx.width()), ctx.pixelsPerByte()), false);
		
		setColor(alpha(Color.WHITE, 0.5F));
		
		List<Pointer>     ptrs    =new ArrayList<>();
		ParsedFrame       parsed  =cFrame.parsed();
		ChunkDataProvider provider=null;
		
		try{
			Cluster cluster=parsed.getCluster().orElseGet(()->{
				try{
					var c=new Cluster(MemoryData.build().withRaw(bytes).asReadOnly().build());
//					LogUtil.println("parsed cluster at frame", frameIndex);
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

//				LogUtil.printTable("instance", "");
				
				DrawB drawByte=(stream, color, withChar, force)->drawBytes(bytes, filled, ctx, stream, color, withChar, force);
				long  pos     =cluster.getFirstChunk().getPtr().getValue();
				while(pos<bytes.length){
					try{
						for(Chunk chunk : new PhysicalChunkWalker(cluster.getChunk(ChunkPointer.of(pos)))){
							pos=chunk.dataEnd();
							if(referenced.contains(chunk)){
								fillChunk(drawByte, chunk, ch->ch, true, true);
							}
							fillChunk(bytes, filled, ctx, ptrs, cluster, chunk);
						}
					}catch(MalformedPointerException e){
						var p=(int)pos;
						drawBytes(bytes, filled, ctx, ()->IntStream.of(p), Color.RED, true, true);
						pos++;
					}
				}
				
				annotateStruct(ctx, cluster,
				               new LinkedList<>(), root,
				               cluster.getFirstChunk().getPtr().makeReference(),
				               FixedContiguousStructPipe.of(root.getThisStruct()),
				               ptrs::add, true);
			}else{
				provider=ChunkDataProvider.newVerySimpleProvider(MemoryData.build().withRaw(bytes).build());
				
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
							fillChunk(bytes, filled, ctx, ptrs, provider, chunk);
						}
					}catch(MalformedPointerException e){
						var p=(int)pos;
						drawBytes(bytes, filled, ctx, ()->IntStream.of(p), Color.RED, true, true);
						pos++;
					}
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
		
		drawBytes(bytes, filled, ctx, ()->IntStream.range(0, bytes.length).filter(((IntPredicate)filled::get).negate()), alpha(Color.GRAY, 0.5F), true, false);
		
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
	
	private void fillChunk(byte[] bytes, BitSet filled, RenderContext ctx, List<Pointer> ptrs, ChunkDataProvider provider, Chunk chunk) throws IOException{
		annotateStruct(ctx, provider, new LinkedList<>(), chunk, null, Chunk.PIPE, ptrs::add, true);
		if(chunk.dataEnd()>bytes.length){
			drawBytes(bytes, filled, ctx, ()->IntStream.range(bytes.length, (int)chunk.dataEnd()), new Color(0, 0, 0, 0.2F), false, true);
		}
	}
	
	private void drawBytes(byte[] bytes, BitSet filled, RenderContext ctx, Supplier<IntStream> stream, Color color, boolean withChar, boolean force){
		Supplier<IntStream> ints=()->stream.get().filter(i->{
			if(i<bytes.length){
				return force||!filled.get(i);
			}
			return true;
		});
		
		var col       =color;
		var bitColor  =col;
		var background=mul(col, 0.5F);
		
		setColor(background);
		try(var ignored=bulkDraw(DrawMode.QUADS)){
			ints.get().filter(i->i<bytes.length).forEach(i->{
				int   xi=i%ctx.width(), yi=i/ctx.width();
				float xF=ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
				
				fillQuad(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte());
			});
		}
		
		setColor(alpha(Color.RED, color.getAlpha()/255F));
		try(var ignored=bulkDraw(DrawMode.QUADS)){
			ints.get().filter(i->i>=bytes.length).forEach(i->{
				int   xi=i%ctx.width(), yi=i/ctx.width();
				float xF=ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
				
				fillQuad(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte());
			});
		}
		
		setColor(bitColor);
		try(var ignored=bulkDraw(DrawMode.QUADS)){
			ints.get().filter(i->i<bytes.length).forEach(i->{
				int   b =bytes[i]&0xFF;
				int   xi=i%ctx.width(), yi=i/ctx.width();
				float xF=ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
				
				for(FlagReader flags=new FlagReader(b, 8);flags.remainingCount()>0;){
					try{
						if(flags.readBoolBit()){
							fillBit(xF, yF, flags.readCount()-1, 0, 0);
						}
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
			});
		}
		
		if(withChar){
			setColor(new Color(1, 1, 1, bitColor.getAlpha()/255F*0.6F));
			ints.get().filter(i->i<bytes.length).filter(i->canFontDisplay(bytes[i])).forEach(i->{
				int   xi=i%ctx.width(), yi=i/ctx.width();
				float xF=ctx.pixelsPerByte()*xi, yF=ctx.pixelsPerByte()*yi;
				
				drawStringIn(Character.toString((char)bytes[i]), new Rect(xF, yF, ctx.pixelsPerByte(), ctx.pixelsPerByte()), true);
			});
		}
		
		ints.get().filter(i->i<bytes.length).forEach(filled::set);
	}
	private void drawBackgroundDots(){
		setColor(errorMode?Color.RED.darker():Color.LIGHT_GRAY);
		
		try(var ignored=bulkDraw(DrawMode.QUADS)){
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
			boolean match=!getFilter().isEmpty()&&filterMatchAt(i);
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
	
	protected boolean filterMatchAt(int i){
		return getFrame(i).data().exceptionContains(getFilter());
	}
	
	private void drawFilter(){
		if(!isWritingFilter()) return;
		setColor(Color.WHITE);
		initFont(1);
		drawStringIn("Filter: "+getFilter(), new Rect(0, 0, getWidth(), getHeight()), true, true);
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
		
		var start=ptr.from();
		var end  =ptr.to();
		
		int pSiz=ptr.size();
		
		Color col;
		if(parsed.lastHoverChunk!=null&&new ChainWalker(parsed.lastHoverChunk).stream().anyMatch(ch->ch.rangeIntersects(ptr.from()))){
			col=mix(chunkBaseColor(parsed.lastHoverChunk), ptr.color(), 0.5F);
		}else{
			col=ptr.color();
		}
		
		setColor(alpha(col, 0.5F));
		
		if(pSiz>1&&LongStream.range(start, start+pSiz).noneMatch(i->i%ctx.width()==0)){
			setColor(alpha(col, 0.1F));
			setStroke(sHalf*ptr.widthFactor());
			drawLine(ctx.width(), start, start+pSiz-1);
			setStroke(sFul*ptr.widthFactor());
			setColor(alpha(col, 0.5F));
		}
		
		long
			xPosFrom=start%ctx.width(),
			yPosFrom=start/ctx.width(),
			xPosTo=end%ctx.width(),
			yPosTo=end/ctx.width();
		
		var rand=new Random((start<<32)+end);
		double
			offScale=Math.sqrt(MathUtil.sq(xPosFrom-xPosTo)+MathUtil.sq(yPosFrom-yPosTo))/3,
			xFromOff=(rand.nextDouble()-0.5)*offScale,
			yFromOff=(rand.nextDouble()-0.5)*offScale,
			xToOff=(rand.nextDouble()-0.5)*offScale,
			yToOff=(rand.nextDouble()-0.5)*offScale,
			
			xFromOrg=xPosFrom+0.5,
			yFromOrg=yPosFrom+0.5,
			xToOrg=xPosTo+0.5,
			yToOrg=yPosTo+0.5,
			
			xFrom=xFromOrg+xFromOff,
			yFrom=yFromOrg+yFromOff,
			xTo=xToOrg+xToOff,
			yTo=yToOrg+yToOff;
		
		if(xFrom<0||xFrom>getWidth()) xFrom=xFromOrg-xFromOff;
		if(yFrom<0||yFrom>getHeight()) yFrom=yFromOrg-yFromOff;
		if(xTo<0||xTo>getWidth()) xTo=xToOrg-xToOff;
		if(yTo<0||yTo>getHeight()) yTo=yToOrg-yToOff;
		
		var handles=new double[4][2];
		handles[0][0]=xFromOrg;
		handles[0][1]=yFromOrg;
		handles[1][0]=xFrom;
		handles[1][1]=yFrom;
		handles[2][0]=xTo;
		handles[2][1]=yTo;
		handles[3][0]=xToOrg;
		handles[3][1]=yToOrg;
		drawPath(handles, true);
		
		if(!ptr.message().isEmpty()){
			float
				x=(float)(xFrom+xTo)/2*ctx.pixelsPerByte(),
				y=(float)(yFrom+yTo)/2*ctx.pixelsPerByte();
			setColor(col);
			initFont(0.3F*ptr.widthFactor());
			fontScale=Math.max(fontScale, 15);
			int msgWidth=ptr.message().length();
			int space   =(int)(getWidth()-x);
			
			var w=getStringBounds(ptr.message()).width();
			while(w>space*1.5){
				msgWidth--;
				w=getStringBounds(ptr.message().substring(0, msgWidth)).width();
			}
			var lines=TextUtil.wrapLongString(ptr.message(), msgWidth);
			y-=fontScale/2F*lines.size();
			for(String line : lines){
				drawStringIn(line, new Rect(x, y, space, ctx.pixelsPerByte()), false, true);
				y+=fontScale;
			}
		}
	}
	
	private void drawError(ParsedFrame parsed){
		if(parsed.displayError==null) return;
//		parsed.displayError.printStackTrace();
		initFont(0.2F);
		fontScale=Math.max(fontScale, 12);
		
		
		var msg       =DrawUtils.errorToMessage(parsed.displayError);
		var lines     =msg.split("\n");
		var bounds    =Arrays.stream(lines).map(this::getStringBounds).toList();
		var totalBound=bounds.stream().reduce((l, r)->new GLFont.Bounds(Math.max(l.width(), r.width()), l.height()+r.height())).orElseThrow();
		
		setColor(alpha(Color.RED.darker(), 0.2F));
		fillQuad(0, getHeight()-totalBound.height()-25, totalBound.width()+20, totalBound.height()+20);
		setColor(alpha(Color.WHITE, 0.8F));
		
		var rect=new Rect(10, getHeight()-totalBound.height()-20, totalBound.width(), fontScale);
		for(int i=0;i<lines.length;i++){
			String line =lines[i];
			var    bound=bounds.get(i);
			rect.height=bound.height();
			rect.y=(Math.round(getHeight()-totalBound.height()+bounds.stream().limit(i).mapToDouble(GLFont.Bounds::height).sum())-15);
			drawStringIn(line, rect, false, true);
		}
	}
	
	private void drawMouse(RenderContext ctx, CachedFrame frame){
		var bytes =frame.data().data();
		var parsed=frame.parsed;
		
		int xByte=(int)(getMouseX()/ctx.pixelsPerByte());
		if(xByte>=ctx.width()) return;
		int yByte    =(int)(getMouseY()/ctx.pixelsPerByte());
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
		int x=(int)(xByte*ctx.pixelsPerByte());
		int y=(int)((yByte-0.1)*ctx.pixelsPerByte());
		
		var bounds=getStringBounds(s);
		x=(int)Math.min(Math.max(0, x-bounds.width()/2+ctx.pixelsPerByte()/2F), getWidth()-Math.ceil(bounds.width()));
		y=Math.max(y, (int)Math.ceil(bounds.height()));
		
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
		int xByte=(int)(getMouseX()/ctx.pixelsPerByte());
		if(xByte>=ctx.width()){
			parsed.lastHoverChunk=null;
			return;
		}
		int yByte    =(int)(getMouseY()/ctx.pixelsPerByte());
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
	
	private record AnnotateStackFrame(IOInstance<?> instance, Reference ref){}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> void annotateStruct(RenderContext ctx,
	                                                      ChunkDataProvider cluster, List<AnnotateStackFrame> stack,
	                                                      T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                      Consumer<Pointer> pointerRecord, boolean annotate) throws IOException{
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return;
		var fieldOffset=0L;
		
		AnnotateStackFrame frame=new AnnotateStackFrame(instance, reference);
		try{
			if(stack.contains(frame)) return;
			stack.add(frame);
			
			var typeHash=instance.getThisStruct().getType().getName().hashCode();
			
			Random rand=new Random();
			setStroke(4);
			
			var iterator=makeFieldIterator(instance, pipe);
			
			while(iterator.hasNext()){
				IOField<T, Object> field=iterator.next();
				try{
					
					var col=makeCol(rand, typeHash, field);
					
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
						var acc=field.getAccessor();
						if(acc!=null&&acc.hasAnnotation(IOType.Dynamic.class)){
							
							var inst=field.get(instance);
							if(inst==null||IOFieldPrimitive.isPrimitive(inst.getClass())||inst.getClass()==String.class){
								if(annotate) annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								continue;
							}
							if(inst instanceof IOInstance<?> ioi){
								annotateStruct(ctx, cluster, stack, (T)ioi, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), ioi.getThisStruct()), pointerRecord, annotate);
								continue;
							}
							LogUtil.printlnEr("unmanaged dynamic type", inst);
							
							continue;
						}
						
						if(field instanceof IOField.Ref refO){
							IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
							var               ref     =refField.getReference(instance);
							boolean           diffPos =true;
							if(!ref.isNull()){
								var from=(int)trueOffset;
								var to  =(int)ref.calcGlobalOffset(cluster);
								diffPos=from!=to;
								if(diffPos){
									pointerRecord.accept(new Pointer(from, to, (int)size, col, refField.toString(), 1));
								}
							}
							
							if(annotate) annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
							if(!diffPos){
								int xByte    =(int)(getMouseX()/ctx.pixelsPerByte());
								int yByte    =(int)(getMouseY()/ctx.pixelsPerByte());
								int byteIndex=yByte*ctx.width()+xByte;
								
								var from=trueOffset;
								var to  =from+size;
								if(from<=byteIndex&&byteIndex<to){
									diffPos=true;
								}
							}
							annotateStruct(ctx, cluster, stack, refField.get(instance), ref, refField.getReferencedPipe(instance), pointerRecord, diffPos);
							
							continue;
						}
						if(field instanceof BitFieldMerger<T> merger){
							int bitOffset=0;
							for(IOField.Bit<T, ?> bit : merger.getGroup()){
								
								var bCol=makeCol(rand, typeHash, bit);
								var siz =bit.getSizeDescriptor().calcUnknown(instance);
								
								if(annotate) annotateBitField(cluster, ctx, instance, bit, bCol, bitOffset, siz, reference, fieldOffset);
								bitOffset+=siz;
							}
							continue;
						}
						if(acc==null){
							throw new RuntimeException("unknown field "+field);
						}
						
						if(UtilL.instanceOf(acc.getType(), ChunkPointer.class)){
							
							var ch=(ChunkPointer)field.get(instance);
							
							if(annotate) annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
							
							if(!ch.isNull()){
								var msg=field.toString();
								try{
									annotateStruct(ctx, cluster, stack, ch.dereference(cluster), null, Chunk.PIPE, pointerRecord, true);
								}catch(Exception e){
									msg=msg+"\n"+DrawUtils.errorToMessage(e);
									col=Color.RED;
								}
								pointerRecord.accept(new Pointer(trueOffset, ch.getValue(), (int)size, col, msg, 0.8F));
							}
						}else if(IOFieldPrimitive.isPrimitive(acc.getType())||Stream.of(INumber.class, Enum.class).anyMatch(c->UtilL.instanceOf(acc.getType(), c))){
							if(annotate){
								setColor(col);
								if(sizeDesc.getWordSpace()==WordSpace.BIT){
									annotateBitField(cluster, ctx, instance, field, col, 0, size, reference, fieldOffset);
								}else{
									annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								}
							}
						}else{
							var typ=acc.getType();
							if(UtilL.instanceOf(typ, IOInstance.class)){
								var inst=(IOInstance<?>)field.get(instance);
								if(inst!=null){
									annotateStruct(ctx, cluster, stack, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), pointerRecord, annotate);
								}
								continue;
							}
							if(typ.isArray()&&IOInstance.isManaged(typ.componentType())){
								var inst  =(IOInstance<?>[])field.get(instance);
								var arrSiz=Array.getLength(inst);
								
								StructPipe elementPipe=ContiguousStructPipe.of(Struct.ofUnknown(typ.componentType()));
								long       arrOffset  =0;
								for(int i=0;i<arrSiz;i++){
									var val=(IOInstance)Array.get(inst, i);
									annotateStruct(ctx, cluster, stack, val, reference.addOffset(fieldOffset+arrOffset), elementPipe, pointerRecord, annotate);
									arrOffset+=elementPipe.getSizeDescriptor().calcUnknown(val, WordSpace.BYTE);
								}
								continue;
							}
							if(typ==String.class){
								if(annotate) annotateByteField(cluster, ctx, pointerRecord, instance, field, col, reference, Range.fromSize(fieldOffset, size));
								continue;
							}
							LogUtil.printlnEr("unmanaged draw type:", typ.toString());
						}
					}finally{
						fieldOffset+=sizeDesc.mapSize(WordSpace.BYTE, size);
					}
				}catch(Throwable e){
					String instStr=instanceErrStr(instance);
					throw new RuntimeException("failed to annotate "+field+" in "+instStr, e);
				}
			}
		}finally{
			stack.remove(frame);
		}
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
	
	private Color makeCol(Random rand, int typeHash, IOField<?, ?> field){
		
		rand.setSeed(typeHash);
		float typeHue=calcHue(rand);
		
		rand.setSeed(field.getName().hashCode());
		float fieldHue=calcHue(rand);
		
		float mix=0.4F;
//		var   hue=typeHue;
		var hue=typeHue*mix+fieldHue*(1-mix);
		
		float brightness=1;
		float saturation=calcSaturation(rand);
		
		return new Color(Color.HSBtoRGB(hue, saturation, brightness));
	}
	
	private float calcSaturation(Random rand){
		float saturation;
//		saturation=0.8F;
		saturation=rand.nextFloat()*0.4F+0.6F;
		return saturation;
	}
	
	private float calcHue(Random rand){
		float[] hues={
			0.1F,
			1,
			2
		};
		
		float hueStep=hues[rand.nextInt(hues.length)]/3F;
		
		float hueOffset=MathUtil.sq(rand.nextFloat());
		if(rand.nextBoolean()) hueOffset*=-1;
		hueOffset/=600;
//		LogUtil.printTable("col", hueStep, "off", hueOffset);
		var hue=hueStep+hueOffset;
		return hue;
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
	
}
