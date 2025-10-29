package com.lapissea.dfs.inspect.display.renderers;

import java.util.List;

public final class CountingPrimitiveBuffer implements PrimitiveBuffer{
	
	private final FontRednerer fontRednerer;
	
	private long count;
	
	public CountingPrimitiveBuffer(FontRednerer fontRednerer){
		this.fontRednerer = fontRednerer;
	}
	
	@Override
	public FontRednerer getFontRender(){
		return fontRednerer;
	}
	@Override
	public void renderMeshes(List<Geometry.IndexedMesh> mesh){
		count += mesh.size();
	}
	@Override
	public void renderLines(List<? extends Geometry.Path> paths){
		count += paths.size();
	}
	@Override
	public void renderFont(List<MsdfFontRender.StringDraw> strings){
		count += strings.size();
	}
	@Override
	public void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
		count++;
	}
	
	public long getCount(){
		return count;
	}
	
	@Override
	public int tokenCount(){
		return (int)count;
	}
	@Override
	public Iterable<TokenSet> tokens(){
		throw new UnsupportedOperationException("this buffer is meant just for counting calls");
	}
}
