package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.GridUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class MultiRendererBuffer implements VulkanResource{
	
	private sealed interface TokenSet{
		record Lines(List<Geometry.Path> paths) implements TokenSet{
			@Override
			public String toString(){
				return "Lines{paths = " + paths.size() + ", points = " + paths.stream().mapToInt(e -> e.toPoints().points().size()).sum() + '}';
			}
		}
		
		record Meshes(List<Geometry.IndexedMesh> meshes) implements TokenSet{
			@Override
			public String toString(){
				return "Meshes{meshes = " + meshes.size() + ", verts = " + meshes.stream().mapToInt(e -> e.verts().size()).sum() + '}';
			}
		}
		
		record Strings(List<MsdfFontRender.StringDraw> strings) implements TokenSet{
			@Override
			public String toString(){
				return "Strings{strings = " + strings.size() + ", chars = " + strings.stream().mapToInt(e -> e.string().length()).sum() + '}';
			}
		}
		
		record ByteEvents(List<ByteGridRender.RenderToken> tokens) implements TokenSet{
			@Override
			public String toString(){
				return "ByteEvents{tokens = " + tokens.size() + '}';
			}
		}
	}
	
	
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
		getTokenSet(TokenSet.Meshes.class).meshes.add(mesh);
	}
	
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
	
	public void renderFont(MsdfFontRender.StringDraw... paths){ renderFont(Arrays.asList(paths)); }
	public void renderFont(List<MsdfFontRender.StringDraw> strings){
		if(strings.isEmpty()) return;
		getTokenSet(TokenSet.Strings.class).strings.addAll(strings);
	}
	
	public void renderBytes(DeviceGC deviceGC, long dataOffset, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents) throws VulkanCodeException{
		var token = byteGridRender.record(deviceGC, gridRes, dataOffset, data, ranges, ioEvents);
		getTokenSet(TokenSet.ByteEvents.class).tokens.add(token);
	}
	
	public void reset(){
		sets.clear();
		gridRes.reset();
		indexedRes.reset();
		fontRes.reset();
	}
	
	public void submit(DeviceGC deviceGC, GridUtils.ByteGridSize gridSize, CommandBuffer cmdBuffer) throws VulkanCodeException{
		var viewSize = gridSize.windowSize();

//		LogUtil.println(Iters.concat1N("=========== FRAME TOKENS ===========", Iters.from(sets).map(Object::toString)).joinAsStr("\n"));
		
		for(TokenSet set : sets){
			switch(set){
				case TokenSet.Lines(var paths) -> {
					var token = lineRenderer.record(deviceGC, indexedRes, paths);
					lineRenderer.submit(viewSize, cmdBuffer, viewMatrix(viewSize), List.of(token));
				}
				case TokenSet.Strings(var strings) -> {
					var token = fontRender.record(deviceGC, fontRes, strings);
					fontRender.submit(viewSize, cmdBuffer, List.of(token));
				}
				case TokenSet.ByteEvents(var tokens) -> {
					byteGridRender.submit(viewSize, cmdBuffer, new Matrix4f().scale(gridSize.byteSize()), gridSize.bytesPerRow(), tokens);
				}
				case TokenSet.Meshes(var meshes) -> {
					for(Geometry.IndexedMesh mesh : meshes){
						var token = indexedRenderer.record(deviceGC, indexedRes, mesh);
						if(token == null) continue;
						indexedRenderer.submit(viewSize, cmdBuffer, viewMatrix(viewSize), List.of(token));
					}
				}
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
