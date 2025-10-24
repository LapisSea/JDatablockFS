package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.DrawUtils.Range;
import com.lapissea.dfs.tools.DrawUtils.Rect;
import com.lapissea.dfs.tools.DrawUtilsVK;
import com.lapissea.dfs.tools.newlogger.display.IndexBuilder;
import com.lapissea.dfs.tools.newlogger.display.VertexBuilder;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.GridUtils;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public final class MergingPrimitiveBuffer implements PrimitiveBuffer{
	
	public record SpatialToken<T extends TokenSet>(T tokens, RectSet areas){ }
	
	public final List<SpatialToken<?>> tokens = new ArrayList<>();
	
	private final FontRednerer           fontRednerer;
	private final GridUtils.ByteGridSize gridSize;
	
	private final boolean merge;
	
	public MergingPrimitiveBuffer(FontRednerer fontRednerer, GridUtils.ByteGridSize gridSize, boolean merge){
		this.fontRednerer = fontRednerer;
		this.gridSize = gridSize;
		this.merge = merge;
	}
	
	public IterablePP<TokenSet> tokens(){
		return Iters.from(tokens).map(SpatialToken::tokens);
	}
	
	private <T extends TokenSet> SpatialToken<T> getTokenSet(Class<T> type){
		if(tokens.isEmpty()){
			var tokenSet = newTokenSet(type);
			tokens.add(tokenSet);
			return tokenSet;
		}
		var last = tokens.getLast();
		if(!type.isInstance(last.tokens)){
			var tokenSet = newTokenSet(type);
			tokens.add(tokenSet);
			return tokenSet;
		}
		//noinspection unchecked
		return (SpatialToken<T>)last;
	}
	
	private <T extends TokenSet> SpatialToken<T> newTokenSet(Class<T> type){
		try{
			T t = type.getConstructor(List.class).newInstance(new ArrayList<>());
			return new SpatialToken<>(t, new RectSet());
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("Failed to instantiate TokenSet", e);
		}
	}
	
	@Override
	public FontRednerer getFontRender(){
		return fontRednerer;
	}
	
	private record BoxPair<T>(T val, Rect box){ }
	
	@Override
	public void renderMeshes(List<Geometry.IndexedMesh> mesh){
		merge(
			TokenSet.Meshes.class,
			Iters.from(mesh).map(e -> new BoxPair<>(e, e.boundingBox())),
			(mTokens, m) -> {
				var meshes = mTokens.meshes();
				if(meshes.isEmpty() || !merge){
					meshes.add(m);
					return;
				}
				var lastM = meshes.getLast();
				lastM.add(m);
			}
		);
	}
	
	@Override
	public void renderLines(Iterable<? extends Geometry.Path> paths){
		merge(
			TokenSet.Lines.class,
			Iters.from(paths).map(e -> new BoxPair<>(e, e.boundingBox())),
			TokenSet.Lines::add
		);
	}
	
	@Override
	public void renderFont(List<MsdfFontRender.StringDraw> strings){
		var frNoCap = getFontRender();
		merge(
			TokenSet.Strings.class,
			Iters.from(strings).map(e -> new BoxPair<>(e, e.boundingBox(frNoCap))),
			TokenSet.Strings::add
		);
	}
	
	private <T extends TokenSet, V> void merge(Class<T> tokType, IterablePP<BoxPair<V>> values, BiConsumer<T, V> add){
		List<BoxPair<V>> remaining = values.toModList();
		if(remaining.isEmpty()) return;
		
		
		List<BoxPair<V>> toAdd;
		if(merge){
			var toHead = new ArrayList<BoxPair<V>>();
			for(var tok : tokens.reversed()){
				var areas = tok.areas;
				if(tokType.isInstance(tok.tokens)){
					for(var e : remaining){
						add.accept((T)tok.tokens, e.val);
						areas.add(e.box);
					}
					remaining.clear();
					break;
				}else{
					remaining.removeIf(e -> {
						if(areas.overlaps(e.box)){
							toHead.add(e);
							return true;
						}
						return false;
					});
					if(remaining.isEmpty()) break;
				}
			}
			toAdd = toHead;
			if(toAdd.isEmpty() && !remaining.isEmpty()){
				toAdd = remaining;
			}else{
				toHead.addAll(remaining);
			}
		}else{
			toAdd = remaining;
		}
		
		
		if(toAdd.isEmpty()) return;
		var p = getTokenSet(tokType);
		for(var e : toAdd){
			add.accept(p.tokens, e.val);
			p.areas.add(e.box);
		}
	}
	
	@Override
	public void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
		List<Rect> rects = computeBytesRects(ranges, ioEvents);
		
		if(merge){
			outer:
			for(var tok : tokens.reversed()){
				var areas = tok.areas;
				if(tok.tokens instanceof TokenSet.ByteEvents bTok){
					for(Rect rect : rects){
						areas.add(rect);
					}
					bTok.add(dataOffset, data, ranges, ioEvents);
					return;
				}
				
				for(Rect rect : rects){
					if(areas.overlaps(rect)){
						break outer;
					}
				}
			}
		}
		
		var bTok = getTokenSet(TokenSet.ByteEvents.class);
		for(Rect rect : rects){
			bTok.areas.add(rect);
		}
		bTok.tokens.add(dataOffset, data, ranges, ioEvents);
	}
	
	private List<Rect> computeBytesRects(Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
		List<Rect> rects = new ArrayList<>();
		
		for(Range range : Iters.from(ioEvents).map(e -> new Range(e.from(), e.to()))){
			
			var mesh = new Geometry.IndexedMesh(new VertexBuilder(1 + 4*4), new IndexBuilder(1 + 6*4));
			DrawUtilsVK.fillByteRange(gridSize, mesh, Color.RED, range);
			
			
			for(Rect bb : Iters.range(0, mesh.verts().size(), 4).mapToObj(i -> {
				var min = new Vector2f(mesh.verts().getPos(i));
				var max = new Vector2f(min);
				for(Vector2f point : Iters.rangeMap(i + 1, i + 4, mesh.verts()::getPos)){
					min.min(point);
					max.max(point);
				}
				return Rect.ofFromTo(min, max);
			})){
				rects.add(Rect.ofFromTo(bb.x + gridSize.byteSize()*2/3, bb.y + gridSize.byteSize()*2/3, bb.xTo(), bb.yTo()));
			}
		}
		
		var r = Range.fromInts(
			Iters.from(ranges).map(e -> new Range(e.from(), e.to()))
			     .stream().flatMapToLong(Range::longs).distinct().sorted().mapToInt(e -> (int)e)
		);
		
		var mesh = new Geometry.IndexedMesh(new VertexBuilder(), new IndexBuilder());
		for(Range range : r){
			DrawUtilsVK.fillByteRange(gridSize, mesh, Color.RED, range);
		}
		
		Iters.range(0, mesh.verts().size(), 4).mapToObj(i -> {
			var min = new Vector2f(mesh.verts().getPos(i));
			var max = new Vector2f(mesh.verts().getPos(i));
			for(Vector2f point : Iters.rangeMap(i + 1, i + 4, mesh.verts()::getPos)){
				min.min(point);
				max.max(point);
			}
			min.add(3F, 3F);
			max.sub(3F, 3F);
			return Rect.ofFromTo(min, max);
		}).forEach(rects::add);
		return rects;
	}
	
	public String data(){
		return Iters.from(tokens).map(e -> e.tokens).joinAsStr("\n") + "\nSIZE: " + tokens.size();
	}
	
	public List<Geometry.PointsLine> paths(){
		return Iters.from(tokens).flatMap(e -> {
			var r = new RawRandom(e.tokens.getClass().getName().hashCode() + 1);
			var c = new Color(r.nextInt(255), r.nextInt(255), r.nextInt(255));
			
			return Iters.from(e.areas.all()).map(rect -> {
				return new Geometry.PointsLine(
					List.of(
						new Vector2f(rect.x, rect.y),
						new Vector2f(rect.xTo(), rect.y),
						new Vector2f(rect.xTo(), rect.yTo()),
						new Vector2f(rect.x, rect.yTo()),
						new Vector2f(rect.x, rect.y)
					),
					2, c, true
				);
			});
		}).toList();
	}
}
