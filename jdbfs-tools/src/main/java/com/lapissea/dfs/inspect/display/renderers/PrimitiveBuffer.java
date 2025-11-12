package com.lapissea.dfs.inspect.display.renderers;

import com.lapissea.dfs.inspect.display.primitives.Geometry;
import com.lapissea.dfs.inspect.display.primitives.Path;
import com.lapissea.dfs.tools.DrawFont;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public interface PrimitiveBuffer{
	
	interface FontRednerer{
		boolean canDisplay(char c);
		DrawFont.Bounds getStringBounds(String string, float fontScale);
	}
	
	record ByteToken(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){ }
	
	sealed interface TokenSet{
		record ReadyMulti(List<MultiRendererBuffer.RenderToken> readyData) implements TokenSet{
			public void add(MultiRendererBuffer.RenderToken path){
				readyData.add(path);
			}
		}
		
		record Lines(List<Path> paths) implements TokenSet{
			@Override
			public String toString(){
				return "Lines{paths = " + paths.size() + ", points = " + paths.stream().mapToInt(e -> e.toPoints().points().size()).sum() + '}';
			}
			public void add(Collection<? extends Path> mesh){
				paths.addAll(mesh);
			}
			public void add(Path path){
				paths.add(path);
			}
		}
		
		record Meshes(List<Geometry.IndexedMesh> meshes) implements TokenSet{
			@Override
			public String toString(){
				return "Meshes{meshes = " + meshes.size() + ", verts = " + meshes.stream().mapToInt(e -> e.verts().size()).sum() + '}';
			}
			public void add(Collection<Geometry.IndexedMesh> mesh){
				meshes.addAll(mesh);
			}
			public void add(Geometry.IndexedMesh mesh){
				meshes.add(mesh);
			}
		}
		
		record Strings(List<MsdfFontRender.StringDraw> strings) implements TokenSet{
			@Override
			public String toString(){
				return "Strings{strings = " + strings.size() + ", chars = " + strings.stream().mapToInt(e -> e.string().length()).sum() + '}';
			}
			public void add(Collection<MsdfFontRender.StringDraw> strings){
				this.strings.addAll(strings);
			}
			public void add(MsdfFontRender.StringDraw string){
				strings.add(string);
			}
		}
		
		record ByteEvents(List<ByteToken> tokens) implements TokenSet{
			@Override
			public String toString(){
				return "ByteEvents{tokens = " + tokens.size() + '}';
			}
			public void add(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
				tokens.add(new ByteToken(dataOffset, data, ranges, ioEvents));
			}
			public void add(ByteToken token){
				tokens.add(token);
			}
		}
	}
	
	
	FontRednerer getFontRender();
	
	default void renderMesh(Geometry.IndexedMesh mesh)         { renderMeshes(List.of(mesh)); }
	void renderMeshes(List<Geometry.IndexedMesh> mesh);
	
	default void renderLine(Path path)                         { renderLines(List.of(path)); }
	void renderLines(List<? extends Path> paths);
	
	default void renderFont(MsdfFontRender.StringDraw path)    { renderFont(List.of(path)); }
	default void renderFont(MsdfFontRender.StringDraw... paths){ renderFont(Arrays.asList(paths)); }
	void renderFont(List<MsdfFontRender.StringDraw> strings);
	
	void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents);
	
	int tokenCount();
	Iterable<TokenSet> tokens();
}
