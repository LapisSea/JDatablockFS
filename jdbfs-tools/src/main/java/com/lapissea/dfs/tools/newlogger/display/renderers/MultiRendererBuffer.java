package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MultiRendererBuffer implements VulkanResource{
	
	private sealed interface TokenSet{
		record Lines(List<LineRenderer.RToken> tokens) implements TokenSet{ }
		
		record Strings(List<MsdfFontRender.RenderToken> tokens) implements TokenSet{ }
	}
	
	
	private final MsdfFontRender fontRender;
	private final ByteGridRender byteGridRender;
	private final LineRenderer   lineRenderer;
	
	private final ByteGridRender.RenderResource gridRes = new ByteGridRender.RenderResource();
	private final Renderer.IndexedMeshBuffer    lineRes = new Renderer.IndexedMeshBuffer();
	private final MsdfFontRender.RenderResource fontRes = new MsdfFontRender.RenderResource();
	
	private final List<TokenSet> sets = new ArrayList<>();
	
	public MultiRendererBuffer(VulkanCore core) throws VulkanCodeException{
		this.fontRender = new MsdfFontRender(core);
		this.byteGridRender = new ByteGridRender(core);
		this.lineRenderer = new LineRenderer(core);
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
	
	public void renderLines(DeviceGC deviceGC, Iterable<? extends Geometry.Path> paths) throws VulkanCodeException{
		var token = lineRenderer.record(deviceGC, lineRes, paths);
		getTokenSet(TokenSet.Lines.class).tokens.add(token);
	}
	
	public void renderFont(DeviceGC deviceGC, MsdfFontRender.StringDraw... paths) throws VulkanCodeException{ renderFont(deviceGC, Arrays.asList(paths)); }
	public void renderFont(DeviceGC deviceGC, List<MsdfFontRender.StringDraw> strings) throws VulkanCodeException{
		var token = fontRender.record(deviceGC, fontRes, strings);
		getTokenSet(TokenSet.Strings.class).tokens.add(token);
	}
	
	public void renderBytes(DeviceGC deviceGC, byte[] data, Iterable<ByteGridRender.DrawRange> ranges, Iterable<ByteGridRender.IOEvent> ioEvents){
	
	}
	
	public void reset(){
		sets.clear();
		lineRes.reset();
		fontRes.reset();
	}
	
	public void submit(Extent2D viewSize, CommandBuffer cmdBuffer) throws VulkanCodeException{
		for(TokenSet set : sets){
			switch(set){
				case TokenSet.Lines(var tokens) -> {
					lineRenderer.submit(viewSize, cmdBuffer, viewMatrix(viewSize), tokens);
				}
				case TokenSet.Strings(var tokens) -> {
					fontRender.submit(viewSize, cmdBuffer, tokens);
				}
			}
		}
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		gridRes.destroy();
		lineRes.destroy();
		fontRes.destroy();
		fontRender.destroy();
		byteGridRender.destroy();
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
