package com.lapissea.dfs.tools;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.tools.render.RenderBackend;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.IterableLongPP;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;
import org.joml.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public final class DrawUtils{
	
	public record Range(long from, long to){
		
		static class Builder{
			long start;
			long end;
			Range build(){
				return new Range(start, end);
			}
			@Override
			public String toString(){
				return "Builder{" +
				       "start=" + start +
				       ", end=" + end +
				       '}';
			}
		}
		
		public static Range fromSize(long start, long size){
			return new Range(start, start + size);
		}
		
		public static List<Range> fromInts(IntStream stream){
			List<Builder> rangesBuild = new ArrayList<>();
			
			stream.forEach(i -> {
				for(var rowRange : rangesBuild){
					if(rowRange.end == i){
						rowRange.end++;
						return;
					}
					if(rowRange.start - 1 == i){
						rowRange.start--;
						return;
					}
				}
				var r = new Builder();
				r.start = i;
				r.end = i + 1;
				rangesBuild.add(r);
			});
			
			return Iters.from(rangesBuild).map(r -> new Range(r.start, r.end)).toList();
		}
		public static List<Range> fromInts(IterableIntPP indexes){
			List<Builder> rangesBuild = new ArrayList<>();
			
			var iter = indexes.iterator();
			wh:
			while(iter.hasNext()){
				var i = iter.nextInt();
				
				for(var rowRange : rangesBuild){
					if(rowRange.end == i){
						rowRange.end++;
						continue wh;
					}
					if(rowRange.start - 1 == i){
						rowRange.start--;
						continue wh;
					}
				}
				var r = new Builder();
				r.start = i;
				r.end = i + 1;
				rangesBuild.add(r);
			}
			
			return Iters.from(rangesBuild).map(r -> new Range(r.start, r.end)).toModList();
		}
		public static List<Range> filterRanges(List<Range> ranges, LongPredicate filter){
			List<Range> actualRanges = new ArrayList<>();
			var         b            = new Builder();
			
			for(Range range : ranges){
				b.start = range.from();
				b.end = range.from();
				for(long i = range.from(); i<range.to(); i++){
					if(filter.test(i)){
						b.end = i + 1;
						continue;
					}
					if(b.start != b.end){
						actualRanges.add(b.build());
					}
					for(; i<range.to(); i++){
						if(filter.test(i)) break;
					}
					if(i == range.to()) return actualRanges;
					b.start = i;
					b.end = i + 1;
				}
				if(b.start != b.end){
					if(b.start == range.from() && b.end == range.to()){
						actualRanges.add(range);
					}else{
						actualRanges.add(b.build());
					}
				}
			}
			return actualRanges;
		}
		public static List<Range> clamp(List<Range> ranges, long max){
			List<Range> build = null;
			{
				boolean same = true;
				for(int i = 0; i<ranges.size(); i++){
					Range r = ranges.get(i);
					if(same){
						if(r.from>=max || r.to>=max){
							build = new ArrayList<>(ranges.size());
							for(int j = 0; j<i; j++){
								build.add(ranges.get(j));
							}
							same = false;
						}
					}
					if(!same){
						if(r.from>=max) continue;
						if(r.to>=max) build.add(new Range(r.from, max));
					}
				}
				if(build == null){
					build = ranges;
				}
			}
			return build;
		}
		public static IterableLongPP toInts(List<Range> ranges){
			return Iters.from(ranges).flatMapToLong(Range::longsI);
		}
		
		public long size(){
			return to - from;
		}
		public Rect toRect(BinaryGridRenderer.RenderContext ctx){
			return toRect(ctx.width(), ctx.pixelsPerByte());
		}
		public Rect toRect(int width, float pixelsPerByte){
			var xByteFrom = (from%width)*pixelsPerByte;
			var yByteFrom = (from/width)*pixelsPerByte;
			var xByteTo   = xByteFrom + pixelsPerByte*size();
			var yByteTo   = yByteFrom + pixelsPerByte;
			return Rect.ofWH(xByteFrom, yByteFrom, xByteTo - xByteFrom, yByteTo - yByteFrom);
		}
		
		public IntStream ints(){
			return IntStream.range(Math.toIntExact(from), Math.toIntExact(to));
		}
		public LongStream longs(){
			return LongStream.range(from, to);
		}
		public IterableLongPP longsI(){
			return Iters.range(from, to);
		}
		
		public boolean isWithin(Range range){
			return range.from<=from && to<=range.to;
		}
	}
	
	public record Rect(float x, float y, float xTo, float yTo){
		public static Rect ofFromTo(Vector2f from, Vector2f to){
			return ofFromTo(from.x, from.y, to.x, to.y);
		}
		public static Rect ofFromTo(float xFrom, float yFrom, float xTo, float yTo){
			return new Rect(xFrom, yFrom, xTo, yTo);
		}
		public static Rect ofWH(float x, float y, float width, float height){
			return new Rect(x, y, x + width, y + height);
		}
		
		public float width()  { return xTo - x; }
		public float height() { return yTo - y; }
		public float xCenter(){ return (x + xTo)/2; }
		public float yCenter(){ return (y + yTo)/2; }
		public float area()   { return width()*height(); }
		
		public Rect union(Rect other){
			var minXFrom = Math.min(x, other.x);
			var minYFrom = Math.min(y, other.y);
			var maxXTo   = Math.max(xTo(), other.xTo());
			var maxYTo   = Math.max(yTo(), other.yTo());
			return ofFromTo(minXFrom, minYFrom, maxXTo, maxYTo);
		}
		
		public boolean isWithin(Rect other){
			return other.x<=x && xTo()<=other.xTo() &&
			       other.y<=y && yTo()<=other.yTo();
		}
		public boolean overlaps(Rect other){
			return x<other.xTo() && xTo()>other.x &&
			       y<other.yTo() && yTo()>other.y;
		}
		
		public Rect addY(float y){
			return Rect.ofFromTo(x, this.y + y, x, yTo() + y);
		}
		
		@Override
		public int hashCode(){
			return Float.floatToIntBits(x + xTo() + y + yTo());
		}
		@Override
		public String toString(){
			return "{" +
			       "x=" + x +
			       ", y=" + y +
			       ", width=" + width() +
			       ", height=" + height() +
			       '}';
		}
	}
	public static Rect makeBitRect(BinaryGridRenderer.RenderContext ctx, long trueOffset, int bitOffset, long siz){
		var range    = findBestContiguousRange(3, new Range(bitOffset, bitOffset + siz));
		var byteRect = new Range(trueOffset, trueOffset).toRect(ctx);
		var bitRect  = range.toRect(3, ctx.pixelsPerByte()/3);
		
		var x = bitRect.x + byteRect.x;
		var y = bitRect.y + byteRect.y;
		return Rect.ofWH(x, y, bitRect.width(), bitRect.height());
	}
	
	public static Range findBestContiguousRange(int width, Range range){
		var start        = (range.from()/width)*width;
		var nextLineFrom = start + width;
		if(nextLineFrom>=range.to()) return range;
		
		var siz       = range.size();
		var sizBefore = nextLineFrom - range.from();
		var sizAfter  = Math.min(width, siz - sizBefore);
		if(sizBefore>sizAfter) return new Range(range.from(), nextLineFrom);
		return new Range(nextLineFrom, nextLineFrom + sizAfter);
	}
	
	public static String errorToMessage(Throwable e){
		StringBuilder message = new StringBuilder(e.getMessage() == null? "" : e.getMessage());
		var           cause   = e.getCause();
		while(cause != null){
			message.append("\nCause: ");
			if(cause.getMessage() == null) message.append(cause.getClass());
			else message.append(cause.getMessage());
			cause = cause.getCause();
		}
		return message.toString();
	}
	
	public static void fillByteRect(BinaryGridRenderer.RenderContext ctx, long start, long width, long columnCount){
		long xi     = start%ctx.width();
		long yStart = start/ctx.width();
		ctx.renderer().fillQuad(ctx.pixelsPerByte()*xi,
		                        ctx.pixelsPerByte()*yStart,
		                        ctx.pixelsPerByte()*width,
		                        ctx.pixelsPerByte()*columnCount);
	}
	
	public static void fillByteRange(Color color, BinaryGridRenderer.RenderContext ctx, Range range){
		ctx.renderer().setColor(color);
		fillByteRange(ctx, range);
	}
	
	public static void fillByteRange(BinaryGridRenderer.RenderContext ctx, Range range){
		long from = range.from();
		long to   = range.to();
		
		//tail
		long fromX      = from%ctx.width();
		long rightSpace = Math.min(ctx.width() - fromX, to - from);
		if(rightSpace>0){
			fillByteRect(ctx, from, rightSpace, 1);
			from += rightSpace;
		}
		
		//bulk
		long bulkColumns = (to - from)/ctx.width();
		if(bulkColumns>0){
			fillByteRect(ctx, from, ctx.width(), bulkColumns);
			from += bulkColumns*ctx.width();
		}
		
		//head
		if(to>from){
			fillByteRect(ctx, from, to - from, 1);
		}
	}
	
	
	private static double[] deBoor(int k, int degree, int i, double x, double[] knots, double[][] ctrlPoints){
		if(k == 0){
			i = Math.max(0, Math.min(ctrlPoints.length - 1, i));
			return ctrlPoints[i];
		}else{
			double   alpha = (x - knots[i])/(knots[i + degree + 1 - k] - knots[i]);
			double[] p0    = deBoor(k - 1, degree, i - 1, x, knots, ctrlPoints);
			double[] p1    = deBoor(k - 1, degree, i, x, knots, ctrlPoints);
			double[] p     = new double[2];
			p[0] = p0[0]*(1 - alpha) + p1[0]*alpha;
			p[1] = p0[1]*(1 - alpha) + p1[1]*alpha;
			return p;
		}
	}
	
	private static int WhichInterval(double x, double[] knot, int ti){
		int index = -1;
		
		for(int i = 1; i<=ti - 1; i++){
			if(x<knot[i]){
				index = i - 1;
				break;
			}
		}
		if(x == knot[ti - 1]){
			index = ti - 1;
		}
		return index;
	}
	
	private static double[] DeBoor(int _k, double[] T, double[][] handles, double t){
		int i = WhichInterval(t, T, T.length);
		return deBoor(_k, 3, i, t, T, handles);
	}
	
	public static void drawPath(BinaryGridRenderer.RenderContext ctx, double[][] handles, boolean arrow){
		final int _k = 3;
		
		var    tPoints = new double[_k + handles.length + 1];
		double d       = 1.0/(tPoints.length - 1);
		for(int i = 0; i<tPoints.length; i++){
			tPoints[i] = i*d;
		}
		
		if(handles.length<2) return;
		
		try(var ignored = ctx.renderer().bulkDraw(RenderBackend.DrawMode.QUADS)){
			double[] lastPoint = null;
			double   lastAngle = 0;
			double   delta     = 1/64.0;
			double   lastArrow = 0;
			for(double t = tPoints[2]; t<tPoints[5]; t += delta){
				double[] newPoint = DeBoor(_k, tPoints, handles, t);
				if(lastPoint != null){
					var angle = Math.atan2(lastPoint[0] - newPoint[0], lastPoint[1] - newPoint[1]);
					if(angle<0) angle += Math.PI;
					var angleDiff = Math.abs(angle - lastAngle);
					lastAngle = angle;
					
					var minAngle = 0.1;
					
					if(angleDiff<minAngle/2){
						delta = Math.min(1/32D, delta*3/2D);
					}else if(angleDiff>minAngle){
						t -= delta;
						delta /= 3/2D;
						continue;
					}
					boolean didArrow = false;
					if(arrow){
						var mid = (tPoints[5] + tPoints[2])/2;
						if(t<mid && (t + delta)>mid || t - lastArrow>0.1){
							drawArrow(ctx, lastPoint[0], lastPoint[1], newPoint[0], newPoint[1]);
							lastArrow = t;
							didArrow = true;
						}
					}
					if(!didArrow){
						drawPixelLine(ctx, lastPoint[0], lastPoint[1], newPoint[0], newPoint[1]);
					}
				}else{
					drawPixelLine(ctx, handles[0][0], handles[0][1], newPoint[0], newPoint[1]);
				}
				lastPoint = newPoint;
			}
			if(lastPoint != null){
				drawPixelLine(ctx, handles[handles.length - 1][0], handles[handles.length - 1][1], lastPoint[0], lastPoint[1]);
			}
		}
	}
	
	public static void drawArrow(BinaryGridRenderer.RenderContext ctx, double xFrom, double yFrom, double xTo, double yTo){
		drawArrow(ctx.renderer(), ctx.pixelsPerByte(), xFrom, yFrom, xTo, yTo);
	}
	public static void drawArrow(RenderBackend renderer, float scale, double xFrom, double yFrom, double xTo, double yTo){
		double xMid = (xFrom + xTo)/2, yMid = (yFrom + yTo)/2;
		
		double angle = Math.atan2(xTo - xFrom, yTo - yFrom);
		
		double arrowSize = 0.4;
		
		double sin = Math.sin(angle)*arrowSize/2;
		double cos = Math.cos(angle)*arrowSize/2;
		
		drawPixelLine(renderer, scale, xMid + sin, yMid + cos, xMid - sin - cos, yMid - cos + sin);
		drawPixelLine(renderer, scale, xMid + sin, yMid + cos, xMid - sin + cos, yMid - cos - sin);
		drawPixelLine(renderer, scale, xFrom, yFrom, xTo, yTo);
	}
	
	public static void drawPixelLine(BinaryGridRenderer.RenderContext ctx, double xFrom, double yFrom, double xTo, double yTo){
		drawPixelLine(ctx.renderer(), ctx.pixelsPerByte(), xFrom, yFrom, xTo, yTo);
	}
	public static void drawPixelLine(RenderBackend renderer, float scale, double xFrom, double yFrom, double xTo, double yTo){
		renderer.drawLine(xFrom*scale, yFrom*scale, xTo*scale, yTo*scale);
	}
	public static IterablePP<Range> chainRangeResolve(DataProvider cluster, Reference ref, long fieldOffset, long size){
		return Iters.nullTerminated(() -> new Supplier<>(){
			long remaining = size;
			final ChunkChainIO io;
			
			{
				try{
					io = new ChunkChainIO(ref.getPtr().dereference(cluster));
					io.setPos(ref.getOffset() + fieldOffset);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			
			@Override
			public Range get(){
				try{
					while(remaining>0){
						var  cursorOff = io.calcCursorOffset();
						var  cursor    = io.getCursor();
						long cRem      = Math.min(remaining, cursor.getSize() - cursorOff);
						if(cRem == 0){
							if(io.remaining() == 0) return null;
							io.skipExact(cursor.getCapacity() - cursor.getSize());
							continue;
						}
						io.skipExact(cRem);
						remaining -= cRem;
						var start = cursor.dataStart() + cursorOff;
						return new Range(start, start + cRem);
					}
					return null;
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		});
	}
}
