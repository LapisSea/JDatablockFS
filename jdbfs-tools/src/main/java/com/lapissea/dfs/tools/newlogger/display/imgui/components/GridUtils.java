package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.DrawFont;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender.StringDraw;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import imgui.ImGui;
import org.joml.SimplexNoise;
import org.joml.Vector2f;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class GridUtils{
	
	public record ByteGridSize(int bytesPerRow, float byteSize){
		public static ByteGridSize compute(Extent2D windowSize, long byteCount){
			if(byteCount == 0) return new ByteGridSize(1, 1);
			var byteCountL = BigDecimal.valueOf(byteCount);
			
			var aspectRatio = windowSize.width/(double)windowSize.height;
			int bytesPerRow = byteCountL.multiply(BigDecimal.valueOf(aspectRatio))
			                            .sqrt(MathContext.DECIMAL64).setScale(0, RoundingMode.UP)
			                            .intValue();
			
			float byteSize = windowSize.width/(float)bytesPerRow;
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
			return new ByteGridSize(bytesPerRow, byteSize);
		}
	}
	
	public record Rect(float x, float y, float width, float height){
		public Rect scale(float scale){
			return new Rect(x*scale, y*scale, width*scale, height*scale);
		}
	}
	
	static List<Geometry.PointsLine> outlineByteRange(Color color, ByteGridSize gridInfo, DrawUtils.Range range, float lineWidth){
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
				        }).matchFirst() instanceof Match.Some(IterablePP.Idx(var index, var val))){
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
			return new Geometry.PointsLine(List.copyOf(c), lineWidth, color, true);
		}).toList();
	}
	
	static List<Geometry.PointsLine> backgroundDots(Extent2D viewSize, Color color){
		float jitter     = 125;
		int   step       = 25;
		float randX      = (float)ImGui.getTime()/10f;
		float randY      = randX + 10000;
		float noiseScale = 175;
		
		var res = new ArrayList<Geometry.PointsLine>();
		
		for(int x = 0; x<viewSize.width + 2; x += step){
			for(int y = (x/2)%step; y<viewSize.height + 2; y += step){
				float xf = x/noiseScale, yf = y/noiseScale;
				
				var nOff = new Vector2f(SimplexNoise.noise(xf, yf, randX), SimplexNoise.noise(xf, yf, randY));
				var pos  = new Vector2f(x, y).add(nOff.mul(jitter));
				res.add(new Geometry.PointsLine(List.of(
					pos, pos.add(2, 0, new Vector2f())
				), 2F, color, false));
			}
		}
		return res;
	}
	
	static Match<StringDraw> stringDrawIn(MsdfFontRender fontRender, String s, Rect area, Color color, float fontScale, boolean alignLeft){
		if(s.isEmpty()) return Match.empty();
		
		if(area.height<fontScale){
			fontScale = area.height;
		}
		
		float w, h;
		{
			var rect = fontRender.getStringBounds(s, fontScale);
			
			w = rect.width();
			h = rect.height();
			
			if(w>0){
				double scale = (area.width - 1)/w;
				if(scale<0.5){
					float div = scale<0.25? 3 : 2;
					fontScale /= div;
					w = rect.width()/div;
					h = rect.height()/div;
				}
				DrawFont.Bounds sbDots = null;
				while((area.width - 1)/w<0.5){
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
		
		float x = area.x;
		float y = area.y;
		
		x += alignLeft? 0 : Math.max(0, area.width - w)/2F;
		y += h + (area.height - h)/2;
		
		float xScale = 1;
		if(w>0){
			double scale = (area.width - 1)/w;
			if(scale<1){
				xScale = (float)scale;
			}
		}
		
		return Match.of(new StringDraw(fontScale, color, s, x, y, xScale, 0));
	}
}
