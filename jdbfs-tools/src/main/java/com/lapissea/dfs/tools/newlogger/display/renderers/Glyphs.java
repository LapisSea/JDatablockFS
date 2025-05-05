package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.carrotsearch.hppc.CharIntHashMap;
import com.google.gson.GsonBuilder;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class Glyphs{
	
	record Metrics(double emSize, double lineHeight, double ascender, double descender, double underlineY, double underlineThickness){ }
	
	static final class Table{
		
		final int    distanceRange;
		final double size;
		
		final Metrics metrics;
		
		final float[]   advance;
		final boolean[] empty;
		final float[]   x0, x1, y0, y1;
		final float[] u0, u1, v0, v1;
		
		final CharIntHashMap index;
		
		final int missingCharId;
		
		final float fsScale;
		
		public Table(int distanceRange, double size, Metrics metrics, int count, CharIntHashMap index, int missingCharId){
			this.metrics = metrics;
			this.distanceRange = distanceRange;
			this.size = size;
			
			advance = new float[count];
			empty = new boolean[count];
			x0 = new float[count];
			x1 = new float[count];
			y0 = new float[count];
			y1 = new float[count];
			u0 = new float[count];
			u1 = new float[count];
			v0 = new float[count];
			v1 = new float[count];
			
			fsScale = (float)(1/(metrics.ascender() - metrics.descender()));
			this.index = index;
			this.missingCharId = missingCharId;
		}
	}
	
	static Table loadTable(String path){
		record Info(String type, int distanceRange, double size, int width, int height, String yOrigin){ }
		record Bounds(float left, float bottom, float right, float top){ }
		record Glyph(int unicode, float advance, Bounds planeBounds, Bounds atlasBounds){ }
		record AtlasInfo(Info atlas, Metrics metrics, List<Glyph> glyphs, List<?> kerning){ }
		
		AtlasInfo info;
		try(var jsonData = VUtils.class.getResourceAsStream(path)){
			Objects.requireNonNull(jsonData);
			info = new GsonBuilder().create().fromJson(new InputStreamReader(jsonData, StandardCharsets.UTF_8), AtlasInfo.class);
		}catch(Throwable e){
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		var atlas  = info.atlas;
		var glyphs = info.glyphs;
		
		int width = atlas.width, height = atlas.height;
		
		var index = new CharIntHashMap(glyphs.size());
		
		for(int i = 0; i<glyphs.size(); i++){
			var glyph = glyphs.get(i);
			index.put((char)glyph.unicode, i);
		}
		
		var missingCharID = Iters.of('\uFFFD', '?', ' ')
		                         .filter(index::containsKey)
		                         .mapToInt(index::get)
		                         .findFirst()
		                         .orElseThrow(() -> new NoSuchElementException("No default glyph found"));
		
		var table = new Table(atlas.distanceRange, atlas.size, info.metrics, glyphs.size(), index, missingCharID);
		
		for(int i = 0; i<glyphs.size(); i++){
			var glyph = glyphs.get(i);
			table.advance[i] = glyph.advance;
			
			var bounds   = glyph.planeBounds;
			var uvBounds = glyph.atlasBounds;
			
			var empty = bounds == null || uvBounds == null;
			table.empty[i] = empty;
			if(empty) continue;
			
			table.x0[i] = bounds.left;
			table.x1[i] = bounds.right;
			table.y0[i] = bounds.bottom;
			table.y1[i] = bounds.top;
			
			table.u0[i] = uvBounds.left/width;
			table.u1[i] = uvBounds.right/width;
			table.v0[i] = 1 - (uvBounds.top/height);
			table.v1[i] = 1 - (uvBounds.bottom/height);
		}
		
		return table;
	}
}
