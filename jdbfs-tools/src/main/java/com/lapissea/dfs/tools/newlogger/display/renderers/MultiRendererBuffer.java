package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.GridUtils;
import com.lapissea.dfs.tools.newlogger.display.renderers.PrimitiveBuffer.TokenSet;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.util.NotImplementedException;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class MultiRendererBuffer implements VulkanResource{
	
	private final MsdfFontRender      fontRender;
	private final ByteGridRender      byteGridRender;
	private final IndexedMeshRenderer indexedRenderer;
	private final LineRenderer        lineRenderer;
	
	private final ByteGridRender.RenderResource gridRes    = new ByteGridRender.RenderResource();
	private final Renderer.IndexedMeshBuffer    indexedRes = new Renderer.IndexedMeshBuffer();
	private final MsdfFontRender.RenderResource fontRes    = new MsdfFontRender.RenderResource();
	
	private final List<TokenSet> sets = new ArrayList<>();
	
	public MultiRendererBuffer(VulkanCore core) throws VulkanCodeException{
		fontRender = new MsdfFontRender(core);
		byteGridRender = new ByteGridRender(core);
		indexedRenderer = new IndexedMeshRenderer(core);
		lineRenderer = new LineRenderer(indexedRenderer, false);
	}
	
	private <T extends TokenSet> T getTokenSet(Class<T> type){
		if(sets.isEmpty()){
			var tokenSet = newTokenSet(type);
			sets.add(tokenSet);
			return tokenSet;
		}
		var last = sets.getLast();
		if(!type.isInstance(last)){
			var tokenSet = newTokenSet(type);
			sets.add(tokenSet);
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
	
	public void renderMesh(Geometry.IndexedMesh mesh){
		if(mesh.verts().size() == 0) return;
		getTokenSet(TokenSet.Meshes.class).add(mesh);
	}
	
	public void renderLines(Iterable<? extends Geometry.Path> paths){
		var p = getTokenSet(TokenSet.Lines.class).paths();
		if(paths instanceof Collection<? extends Geometry.Path> collection){
			p.addAll(collection);
		}else{
			for(var path : paths){
				p.add(path);
			}
		}
	}
	
	public void renderFont(MsdfFontRender.StringDraw... paths){ renderFont(Arrays.asList(paths)); }
	public void renderFont(List<MsdfFontRender.StringDraw> strings){
		if(strings.isEmpty()) return;
		getTokenSet(TokenSet.Strings.class).strings().addAll(strings);
	}
	
	public void renderBytes(long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents) throws VulkanCodeException{
		getTokenSet(TokenSet.ByteEvents.class).add(dataOffset, data, ranges, ioEvents);
	}
	public void renderReady(MultiRendererBuffer buffer){
		getTokenSet(TokenSet.ReadyMulti.class).readyBuffers().add(buffer);
	}
	public void add(Iterable<TokenSet> tokens) throws VulkanCodeException{
		if(tokens instanceof Collection<TokenSet> coll){
			sets.addAll(coll);
		}else{
			for(TokenSet token : tokens){
				sets.add(token);
			}
		}
	}
	
	public void reset(){
		renderTokens.clear();
		sets.clear();
		gridRes.reset();
		indexedRes.reset();
		fontRes.reset();
	}
	
	private final List<Object> renderTokens = new ArrayList<>();
	
	public void upload(DeviceGC deviceGC) throws VulkanCodeException{
		for(TokenSet set : sets){
			switch(set){
				case TokenSet.Lines(var paths) -> {
					var token = lineRenderer.record(deviceGC, indexedRes, paths);
					renderTokens.add(token);
				}
				case TokenSet.Strings(var strings) -> {
					var token = fontRender.record(deviceGC, fontRes, strings);
					renderTokens.add(token);
				}
				case TokenSet.ByteEvents(var tokens) -> {
					var token = byteGridRender.record(deviceGC, gridRes, tokens);
					renderTokens.add(token);
				}
				case TokenSet.Meshes(var meshes) -> {
					for(Geometry.IndexedMesh mesh : meshes){
						var token = indexedRenderer.record(deviceGC, indexedRes, mesh);
						if(token == null) continue;
						renderTokens.add(token);
					}
				}
				case TokenSet.ReadyMulti(var buffer) -> {
					for(MultiRendererBuffer buff : buffer){
						renderTokens.addAll(buff.renderTokens);
					}
				}
			}
		}
	}
	
	public void submit(GridUtils.ByteGridSize gridSize, Extent2D viewSizeSc, CommandBuffer cmdBuffer) throws VulkanCodeException{
		for(Object renderToken : renderTokens){
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
				case MultiRendererBuffer token -> {
					token.submit(gridSize, viewSizeSc, cmdBuffer);
				}
				default -> throw new NotImplementedException(renderToken.getClass().getName());
			}
		}
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		gridRes.destroy();
		indexedRes.destroy();
		fontRes.destroy();
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
