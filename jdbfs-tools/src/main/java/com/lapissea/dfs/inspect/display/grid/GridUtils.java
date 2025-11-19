package com.lapissea.dfs.inspect.display.grid;

import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.primitives.Geometry;
import com.lapissea.dfs.inspect.display.primitives.IndexBuilder;
import com.lapissea.dfs.inspect.display.primitives.Path;
import com.lapissea.dfs.inspect.display.primitives.VertexBuilder;
import com.lapissea.dfs.inspect.display.renderers.MsdfFontRender.StringDraw;
import com.lapissea.dfs.inspect.display.renderers.PrimitiveBuffer;
import com.lapissea.dfs.inspect.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.DrawFont;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.ImGui;
import org.joml.SimplexNoise;
import org.joml.Vector2f;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GridUtils{
	
	
	public record ByteGridSize(int bytesPerRow, float byteSize, Extent2D windowSize){
		public ByteGridSize{
			if(bytesPerRow<=0) throw new IllegalArgumentException("bytesPerRow can't be negative or zero");
			if(byteSize<=0) throw new IllegalArgumentException("byteSize can't be negative or zero");
			Objects.requireNonNull(windowSize, "windowSize can't be null");
		}
		
		public static ByteGridSize compute(Extent2D windowSize, long byteCount, Match<ByteGridSize> previous){
			if(byteCount == 0) return new ByteGridSize(1, 1, windowSize);
			var byteCountL = BigDecimal.valueOf(byteCount);
			
			
			int   bytesPerRow;
			float byteSize;
			if(previous instanceof Some(var prev) && prev.windowSize.equals(windowSize)){
				bytesPerRow = prev.bytesPerRow;
				byteSize = prev.byteSize;
			}else{
				var aspectRatio = windowSize.width/(double)windowSize.height;
				bytesPerRow = byteCountL.multiply(BigDecimal.valueOf(aspectRatio))
				                        .sqrt(MathContext.DECIMAL64).setScale(0, RoundingMode.UP)
				                        .intValue();
				
				byteSize = windowSize.width/(float)bytesPerRow;
			}
			
			while(true){
				int rows = byteCountL.divide(BigDecimal.valueOf(bytesPerRow), MathContext.DECIMAL32).setScale(0, RoundingMode.UP)
				                     .intValue();
				float totalHeight = rows*byteSize;
				
				if(totalHeight<=windowSize.height){
					break;
				}
				bytesPerRow++;
				byteSize = windowSize.width/(float)bytesPerRow;
			}
			return new ByteGridSize(bytesPerRow, byteSize, windowSize);
		}
		
		public GridRect findBestRectScaled(DrawUtils.Range range){
			return findBestRect(range).scale(byteSize);
		}
		
		public GridRect findBestRect(DrawUtils.Range range){
			var from = range.from();
			var to   = range.to();
			
			var fromRow = from/bytesPerRow;
			var toRow   = to/bytesPerRow;
			if(fromRow == toRow){
				var fromX = from - fromRow*bytesPerRow;
				var toX   = to - toRow*bytesPerRow;
				return new GridRect(fromX, fromRow, toX - fromX, 1);
			}
			
			long fromRowEnd = (fromRow + 1)*bytesPerRow;
			long toRowStart = toRow*bytesPerRow;
			
			long bulkFrom = fromRowEnd;
			
			if(from + bytesPerRow == bulkFrom){
				bulkFrom = from;
			}
			
			long bulkRows = Math.max(0, (toRowStart - bulkFrom)/bytesPerRow);
			if(bulkRows>0){
				var bulkFirstRow = bulkFrom/bytesPerRow;
				return new GridRect(0, bulkFirstRow, bytesPerRow, bulkRows);
			}
			
			long headSize = fromRowEnd - from;
			long tailSize = to - toRowStart;
			
			if(headSize>=tailSize){
				return new GridRect(from%bytesPerRow, fromRow, headSize, 1);
			}else{
				return new GridRect(0, toRow, tailSize, 1);
			}
		}
	}
	
	public static List<Path.PointsLine> outlineByteRange(Color color, ByteGridSize gridInfo, DrawUtils.Range range, float lineWidth){
		record Line(Vector2f a, Vector2f b){
			Line(float xa, float ya, float xb, float yb){
				this(new Vector2f(xa, ya), new Vector2f(xb, yb));
			}
		}
		var lines = new ArrayList<Line>(){
			@Override
			public boolean add(Line line){
				var dirLine = line.a.sub(line.b, new Vector2f()).normalize();
				if(Iters.from(this).enumerate()
				        .filter(e -> {
					        var dist = e.val().b.distanceSquared(line.a);
					        return dist<0.001;
				        })
				        .filter(e -> {
					        var dirE = e.val().a.sub(e.val().b, new Vector2f()).normalize();
					        return Math.abs(dirLine.dot(dirE) - 1)<0.0001;
				        }).matchFirst() instanceof Some(IterablePP.Idx(var index, var val))){
					this.set(index, new Line(val.a, line.b));
					return true;
				}
				return super.add(line);
			}
		};
		
		var gridWidth = gridInfo.bytesPerRow;
		for(var i = range.from(); i<range.to(); i++){
			long x  = i%gridWidth, y = i/gridWidth;
			long x1 = x, y1 = y;
			long x2 = x1 + 1, y2 = y1 + 1;
			
			if(i - range.from()<gridWidth) lines.add(new Line(x1, y1, x2, y1));
			if(range.to() - i<=gridWidth) lines.add(new Line(x1, y2, x2, y2));
			if(x == 0 || i == range.from()) lines.add(new Line(x1, y1, x1, y2));
			if(x2 == gridWidth || i == range.to() - 1) lines.add(new Line(x2, y1, x2, y2));
		}
		
		
		var chains = new ArrayList<List<Vector2f>>();
		var points = new ArrayList<Vector2f>();
		
		var pointsIndexed = Iters.from(lines).enumerate().flatMap(e -> List.of(
			new IterablePP.Idx<>(e.index(), e.val()),
			new IterablePP.Idx<>(e.index(), new Line(e.val().b, e.val().a))
		));
		
		while(!lines.isEmpty()){
			if(points.isEmpty()){
				var l = lines.removeLast();
				points.add(l.a);
				points.add(l.b);
				continue;
			}
			
			var lastPt = points.getLast();
			var en     = pointsIndexed.minBy(e -> e.val().a.distanceSquared(lastPt)).orElseThrow();
			if(en.val().a.distanceSquared(lastPt)<0.0001){
				lines.remove(en.index());
				points.add(en.val().b);
				continue;
			}
			
			var firstPt = points.getFirst();
			en = pointsIndexed.minBy(e -> e.val().a.distanceSquared(firstPt)).orElseThrow();
			if(firstPt.distanceSquared(en.val().a)<0.0001){
				lines.remove(en.index());
				points.addFirst(en.val().b);
				continue;
			}
			
			chains.add(List.copyOf(points));
			points.clear();
		}
		if(!points.isEmpty()) chains.add(points);
		
		return Iters.from(chains).map(c -> {
			for(Vector2f point : c) point.mul(gridInfo.byteSize);
			return new Path.PointsLine(List.copyOf(c), lineWidth, color, true);
		}).toList();
	}
	
	public static Geometry.IndexedMesh backgroundDots(Extent2D viewSize, Color color, float contentScale){
		float jitter     = 125*contentScale;
		float step       = 25*contentScale;
		float randX      = (float)ImGui.getTime()/10f;
		float randY      = randX + 10000;
		float noiseScale = 175*contentScale;
		float pointSize  = 2*contentScale;
		
		int pointCount;
		{
			int numCols  = (int)Math.ceil(viewSize.width/step);
			int rowsEven = (int)Math.ceil(viewSize.height/step);
			int rowsOdd  = (int)Math.ceil((viewSize.height - step/2f)/step);
			if(numCols%2 == 0){
				pointCount = (numCols/2)*rowsEven + (numCols/2)*rowsOdd;
			}else{
				pointCount = ((numCols + 1)/2)*rowsEven + (numCols/2)*rowsOdd;
			}
		}
		
		var colorI = VUtils.toRGBAi4(color);
		var res    = new VertexBuilder(pointCount*4);
		
		for(float x = 0; x<viewSize.width; x += step){
			for(float y = (x/2)%step; y<viewSize.height; y += step){
				float xf = x/noiseScale, yf = y/noiseScale;
				
				var nOff = new Vector2f(SimplexNoise.noise(xf, yf, randX), SimplexNoise.noise(xf, yf, randY));
				var pos  = new Vector2f(x, y).add(nOff.mul(jitter));
				
				res.add(pos, colorI);
				res.add(pos.x + pointSize, pos.y, colorI);
				res.add(pos.x + pointSize, pos.y + pointSize, colorI);
				res.add(pos.x, pos.y + pointSize, colorI);
			}
		}
		
		var index = new IndexBuilder(pointCount*6, IndexBuilder.findType(pointCount*4)).noResize();
		
		var quad = new int[]{0, 2, 1, 0, 3, 2};
		for(int i = 0; i<pointCount; i++){
			index.addOffset(quad, i*4);
		}
		
		return new Geometry.IndexedMesh(res, index);
	}
	
	public static Match<StringDraw> stringDrawIn(PrimitiveBuffer.FontRednerer fontRender, String s, GridRect area, Color color, float fontScale, boolean alignLeft){
		if(s.isEmpty()) return Match.empty();
		
		noneCanRender:
		{
			for(int i = 0, j = s.length(); i<j; i++){
				var c = s.charAt(i);
				if(fontRender.canDisplay(c)){
					break noneCanRender;
				}
			}
			return Match.empty();
		}
		
		if(area.height()<fontScale){
			fontScale = area.height();
		}
		
		float w, h;
		{
			var rect = fontRender.getStringBounds(s, fontScale);
			
			w = rect.width();
			h = rect.height();
			
			if(w>0){
				double scale = (area.width() - 1)/w;
				if(scale<0.5){
					float div = scale<0.25? 3 : 2;
					fontScale /= div;
					w = rect.width()/div;
					h = rect.height()/div;
				}
				DrawFont.Bounds sbDots = null;
				while((area.width() - 1)/w<0.5){
					if(s.isEmpty()) return Match.empty();
					if(s.length() == 1){
						break;
					}
					s = s.substring(0, s.length() - 1).trim();
					rect = fontRender.getStringBounds(s, fontScale);
					if(sbDots == null){
						sbDots = fontRender.getStringBounds("...", fontScale);
					}
					w = rect.width() + sbDots.width();
					h = Math.max(rect.height(), sbDots.height());
				}
			}
		}
		
		float x = area.x();
		float y = area.y();
		
		x += alignLeft? 0 : Math.max(0, area.width() - w)/2F;
		y += h + (area.height() - h)/2;
		
		float xScale = 1;
		if(w>0){
			double scale = (area.width() - 1)/w;
			if(scale<1){
				xScale = (float)scale;
			}
		}
		
		return Match.of(new StringDraw(fontScale, color, s, x, y, xScale, 0));
	}
	
	
	public static Match<Long> calcByteIndex(ByteGridSize gridSize, int mouseX, int mouseY, long byteCount, float zoom){
		if(mouseX<0 || mouseY<0) return Match.empty();
		int xByte = calcX(gridSize.windowSize, mouseX, gridSize.byteSize, zoom);
		int yByte = calcY(gridSize.windowSize, mouseY, gridSize.byteSize, zoom);
		
		int  width     = gridSize.bytesPerRow;
		long byteIndex = yByte*(long)width + xByte;
		
		if(xByte>=width || byteIndex>=byteCount){
			return Match.empty();
		}
		return Match.of(byteIndex);
	}
	private static int calcY(Extent2D viewSize, int mouseY, float pixelsPerByte, float zoom){
		var h    = viewSize.height;
		var offY = (h - h*zoom)*(mouseY/(float)h);
		return (int)((mouseY - offY)/(pixelsPerByte*zoom));
	}
	private static int calcX(Extent2D viewSize, int mouseX, float pixelsPerByte, float zoom){
		var w    = viewSize.width;
		var offX = (w - w*zoom)*(mouseX/(float)w);
		return (int)((mouseX - offX)/(pixelsPerByte*zoom));
	}
	
	static boolean isRangeHovered(Match<Long> hover, long from, long to){
		if(!(hover instanceof Some(var hoverByteIndex))) return false;
		return from<=hoverByteIndex && hoverByteIndex<to;
	}
}
