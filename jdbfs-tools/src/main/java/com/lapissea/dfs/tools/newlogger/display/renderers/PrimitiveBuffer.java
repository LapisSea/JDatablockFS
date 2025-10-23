package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.DrawFont;

import java.util.ArrayList;
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
		record Lines(List<Geometry.Path> paths) implements TokenSet{
			@Override
			public String toString(){
				return "Lines{paths = " + paths.size() + ", points = " + paths.stream().mapToInt(e -> e.toPoints().points().size()).sum() + '}';
			}
			public void add(Geometry.Path path){
				paths.add(path);
			}
		}
		
		record Meshes(List<Geometry.IndexedMesh> meshes) implements TokenSet{
			@Override
			public String toString(){
				return "Meshes{meshes = " + meshes.size() + ", verts = " + meshes.stream().mapToInt(e -> e.verts().size()).sum() + '}';
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
	
	final class SimpleBuffer implements PrimitiveBuffer{
		
		
		public final  List<TokenSet> tokens = new ArrayList<>();
		private final FontRednerer   fontRednerer;
		
		public SimpleBuffer(FontRednerer fontRednerer){
			this.fontRednerer = fontRednerer;
		}
		
		private <T extends TokenSet> T getTokenSet(Class<T> type){
			if(tokens.isEmpty()){
				var tokenSet = newTokenSet(type);
				tokens.add(tokenSet);
				return tokenSet;
			}
			var last = tokens.getLast();
			if(!type.isInstance(last)){
				var tokenSet = newTokenSet(type);
				tokens.add(tokenSet);
				return tokenSet;
			}
			//noinspection unchecked
			return (T)last;
		}
		
		private <T extends TokenSet> T newTokenSet(Class<T> type){
			try{
				return type.getConstructor(List.class).newInstance(new ArrayList<>());
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to instantiate TokenSet", e);
			}
		}
		
		@Override
		public FontRednerer getFontRender(){
			return fontRednerer;
		}
		
		@Override
		public void renderMeshes(List<Geometry.IndexedMesh> mesh){
			getTokenSet(TokenSet.Meshes.class).meshes.addAll(mesh);
		}
		@Override
		public void renderLines(Iterable<? extends Geometry.Path> paths){
			var p = getTokenSet(TokenSet.Lines.class).paths;
			if(paths instanceof Collection<? extends Geometry.Path> collection){
				p.addAll(collection);
			}else{
				for(var path : paths){
					p.add(path);
				}
			}
		}
		@Override
		public void renderFont(List<MsdfFontRender.StringDraw> strings){
			if(strings.isEmpty()) return;
			getTokenSet(TokenSet.Strings.class).strings.addAll(strings);
		}
		@Override
		public void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
			getTokenSet(TokenSet.ByteEvents.class).tokens.add(new ByteToken(dataOffset, data, ranges, ioEvents));
		}
	}
	
	FontRednerer getFontRender();
	
	default void renderMesh(Geometry.IndexedMesh mesh)         { renderMeshes(List.of(mesh)); }
	void renderMeshes(List<Geometry.IndexedMesh> mesh);
	
	default void renderLine(Geometry.Path path)                { renderLines(List.of(path)); }
	void renderLines(Iterable<? extends Geometry.Path> paths);
	
	default void renderFont(MsdfFontRender.StringDraw path)    { renderFont(List.of(path)); }
	default void renderFont(MsdfFontRender.StringDraw... paths){ renderFont(Arrays.asList(paths)); }
	void renderFont(List<MsdfFontRender.StringDraw> strings);
	
	void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents);
}
