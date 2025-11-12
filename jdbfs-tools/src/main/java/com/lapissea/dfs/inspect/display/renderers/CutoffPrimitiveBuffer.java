package com.lapissea.dfs.inspect.display.renderers;

import com.lapissea.dfs.inspect.display.primitives.Geometry;
import com.lapissea.dfs.inspect.display.primitives.Path;

import java.util.List;

public final class CutoffPrimitiveBuffer implements PrimitiveBuffer{
	
	private final PrimitiveBuffer parent;
	private final long            cutoff;
	private       long            count;
	public CutoffPrimitiveBuffer(PrimitiveBuffer parent, long cutoff){
		this.parent = parent;
		this.cutoff = cutoff;
	}
	
	private <T> List<T> trimElements(List<T> elements){
		if(count>=cutoff || elements.isEmpty()) return List.of();
		var remaining = cutoff - count;
		var toAdd     = (int)Math.min(elements.size(), remaining);
		count += toAdd;
		return toAdd != elements.size()? elements.subList(0, toAdd) : elements;
	}
	
	@Override
	public FontRednerer getFontRender(){
		return parent.getFontRender();
	}
	@Override
	public void renderMeshes(List<Geometry.IndexedMesh> mesh){
		var toAdd = trimElements(mesh);
		if(toAdd.isEmpty()) return;
		parent.renderMeshes(toAdd);
	}
	@Override
	public void renderLines(List<? extends Path> paths){
		var toAdd = trimElements(paths);
		if(toAdd.isEmpty()) return;
		parent.renderLines(toAdd);
	}
	@Override
	public void renderFont(List<MsdfFontRender.StringDraw> strings){
		var toAdd = trimElements(strings);
		if(toAdd.isEmpty()) return;
		parent.renderFont(toAdd);
	}
	@Override
	public void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
		if(count == cutoff) return;
		count++;
		parent.renderBytes(dataOffset, data, ranges, ioEvents);
	}
	@Override
	public int tokenCount(){
		return parent.tokenCount();
	}
	@Override
	public Iterable<TokenSet> tokens(){
		return parent.tokens();
	}
}
