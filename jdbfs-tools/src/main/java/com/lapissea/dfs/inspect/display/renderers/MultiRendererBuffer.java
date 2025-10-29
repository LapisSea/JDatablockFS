package com.lapissea.dfs.inspect.display.renderers;

import com.lapissea.dfs.inspect.display.DeviceGC;
import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.grid.GridUtils;
import com.lapissea.dfs.inspect.display.renderers.PrimitiveBuffer.TokenSet;
import com.lapissea.dfs.inspect.display.vk.CommandBuffer;
import com.lapissea.dfs.inspect.display.vk.VulkanCore;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.inspect.display.vk.wrap.Extent2D;
import com.lapissea.util.NotImplementedException;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MultiRendererBuffer implements VulkanResource{
	
	public static final class TokenBuilder implements PrimitiveBuffer{
		private final List<TokenSet> tokens;
		private final FontRednerer   fontRednerer;
		
		public TokenBuilder(FontRednerer fontRednerer){ this(new ArrayList<>(), fontRednerer); }
		public TokenBuilder(List<TokenSet> tokens, FontRednerer fontRednerer){
			this.tokens = tokens;
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
		
		private static <T extends TokenSet> T newTokenSet(Class<T> type){
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
		public void renderMeshes(List<Geometry.IndexedMesh> meshes){
			if(meshes.isEmpty()) return;
			getTokenSet(TokenSet.Meshes.class).add(meshes);
		}
		@Override
		public void renderLines(List<? extends Geometry.Path> paths){
			if(paths.isEmpty()) return;
			getTokenSet(TokenSet.Lines.class).add(paths);
		}
		@Override
		public void renderFont(List<MsdfFontRender.StringDraw> strings){
			if(strings.isEmpty()) return;
			getTokenSet(TokenSet.Strings.class).add(strings);
		}
		@Override
		public void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
			getTokenSet(TokenSet.ByteEvents.class).add(dataOffset, data, ranges, ioEvents);
		}
		
		public void add(Iterable<TokenSet> tokens) throws VulkanCodeException{
			if(tokens instanceof Collection<TokenSet> coll){
				this.tokens.addAll(coll);
			}else{
				for(TokenSet token : tokens){
					this.tokens.add(token);
				}
			}
		}
		
		public void renderReady(MultiRendererBuffer.RenderToken token){
			getTokenSet(TokenSet.ReadyMulti.class).add(token);
		}
		
		@Override
		public int tokenCount(){
			return tokens.size();
		}
		@Override
		public Iterable<TokenSet> tokens(){
			return tokens;
		}
	}
	
	public static final class RenderToken implements Renderer.RenderToken{
		private final List<Renderer.RenderToken> data;
		private RenderToken(List<Renderer.RenderToken> data){
			this.data = data;
		}
	}
	
	public static final class RenderResource implements Renderer.ResourceBuffer{
		private final ByteGridRender.RenderResource gridRes    = new ByteGridRender.RenderResource();
		private final Renderer.IndexedMeshBuffer    indexedRes = new Renderer.IndexedMeshBuffer();
		private final MsdfFontRender.RenderResource fontRes    = new MsdfFontRender.RenderResource();
		
		@Override
		public void destroy() throws VulkanCodeException{
			gridRes.destroy();
			indexedRes.destroy();
			fontRes.destroy();
		}
		@Override
		public void reset(){
			gridRes.reset();
			indexedRes.reset();
			fontRes.reset();
		}
	}
	
	private final MsdfFontRender      fontRender;
	private final ByteGridRender      byteGridRender;
	private final IndexedMeshRenderer indexedRenderer;
	private final LineRenderer        lineRenderer;
	
	public MultiRendererBuffer(VulkanCore core) throws VulkanCodeException{
		fontRender = new MsdfFontRender(core);
		byteGridRender = new ByteGridRender(core);
		indexedRenderer = new IndexedMeshRenderer(core);
		lineRenderer = new LineRenderer(indexedRenderer, false);
	}
	
	public RenderToken upload(RenderResource resource, PrimitiveBuffer data, DeviceGC deviceGC) throws VulkanCodeException{
		List<Renderer.RenderToken> rTokens = new ArrayList<>(data.tokenCount());
		for(TokenSet set : data.tokens()){
			switch(set){
				case TokenSet.Lines(var paths) -> {
					var token = lineRenderer.record(deviceGC, resource.indexedRes, paths);
					if(token != null) rTokens.add(token);
				}
				case TokenSet.Strings(var strings) -> {
					var token = fontRender.record(deviceGC, resource.fontRes, strings);
					if(token != null) rTokens.add(token);
				}
				case TokenSet.ByteEvents(var tokens) -> {
					var token = byteGridRender.record(deviceGC, resource.gridRes, tokens);
					if(token != null) rTokens.add(token);
				}
				case TokenSet.Meshes(var meshes) -> {
					for(Geometry.IndexedMesh mesh : meshes){
						var token = indexedRenderer.record(deviceGC, resource.indexedRes, mesh);
						if(token != null) rTokens.add(token);
					}
				}
				case TokenSet.ReadyMulti(var buffer) -> {
					for(var buff : buffer){
						rTokens.addAll(buff.data);
					}
				}
			}
		}
		return new RenderToken(rTokens);
	}
	
	public void submit(GridUtils.ByteGridSize gridSize, Extent2D viewSizeSc, RenderToken rToken, CommandBuffer cmdBuffer) throws VulkanCodeException{
		for(Object renderToken : rToken.data){
			switch(renderToken){
				case MsdfFontRender.RenderToken token -> {
					fontRender.submit(viewSizeSc, cmdBuffer, List.of(token));
				}
				case ByteGridRender.RenderToken token -> {
					byteGridRender.submit(viewSizeSc, cmdBuffer, new Matrix4f().scale(gridSize.byteSize()), gridSize.bytesPerRow(), List.of(token));
				}
				case IndexedMeshRenderer.RToken token -> {
					indexedRenderer.submit(viewSizeSc, cmdBuffer, viewMatrix(gridSize.windowSize()), List.of(token));
				}
				case MultiRendererBuffer.RenderToken token -> {
					submit(gridSize, viewSizeSc, token, cmdBuffer);
				}
				default -> throw new NotImplementedException(renderToken.getClass().getName());
			}
		}
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		fontRender.destroy();
		byteGridRender.destroy();
		indexedRenderer.destroy();
		lineRenderer.destroy();
	}
	
	public MsdfFontRender getFontRender(){
		return fontRender;
	}
	
	private static Matrix3x2f viewMatrix(Extent2D viewSize){
		return new Matrix3x2f()
			       .translate(-1, -1)
			       .scale(2F/viewSize.width, 2F/viewSize.height);
	}
}
