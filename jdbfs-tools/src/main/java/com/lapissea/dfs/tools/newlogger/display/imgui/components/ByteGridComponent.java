package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import imgui.type.ImBoolean;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MsdfFontRender fontRender;
	private final ByteGridRender byteGridRender;
	private final LineRenderer   lineRenderer;
	
	private final ByteGridRender.RenderResource grid1Res = new ByteGridRender.RenderResource();
	private final LineRenderer.RenderResource   lineRes  = new LineRenderer.RenderResource();
	private final MsdfFontRender.RenderResource fontRes  = new MsdfFontRender.RenderResource();
	
	public ByteGridComponent(
		ImBoolean open, VulkanCore core,
		MsdfFontRender fontRender, ByteGridRender byteGridRender, LineRenderer lineRenderer
	) throws VulkanCodeException{
		super(core, open);
		
		this.fontRender = fontRender;
		this.byteGridRender = byteGridRender;
		this.lineRenderer = lineRenderer;
		
		byte[] bytes = new RawRandom(10).nextBytes(32*32);
		
		byteGridRender.record(
			grid1Res,
			bytes,
			List.of(
				new ByteGridRender.DrawRange(0, bytes.length/2, Color.green.darker()),
				new ByteGridRender.DrawRange(bytes.length/2, bytes.length, Color.RED.darker())
			),
			List.of(
				new ByteGridRender.IOEvent(6, 10, ByteGridRender.IOEvent.Type.WRITE),
				new ByteGridRender.IOEvent(8, 20, ByteGridRender.IOEvent.Type.READ)
			)
		);
		
	}
	
	@Override
	protected boolean needsRerender(){ return true; }
	
	@Override
	protected void renderBackbuffer(Extent2D viewSize, CommandBuffer cmdBuffer) throws VulkanCodeException{
		
		renderAutoSizeByteGrid(viewSize, cmdBuffer);
		
		List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
		
		testFontWave(sd);
		
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
		fontRender.render(viewSize, cmdBuffer, fontRes, sd);
		
		renderDecimatedCurve(viewSize, cmdBuffer);
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		super.unload(tScope);
		grid1Res.destroy();
		lineRes.destroy();
		fontRes.destroy();
	}
	
	private static void testFontWave(List<MsdfFontRender.StringDraw> sd){
		var pos = 0F;
		for(int i = 0; i<40; i++){
			float size = 1 + (i*i)*0.2F;
			
			var t = (System.currentTimeMillis())/500D;
			var h = (float)Math.sin(t + pos/(10 + i*3))*50;
			sd.add(new MsdfFontRender.StringDraw(size, Color.GREEN.darker(),
			                                     "a", 20 + pos, 70 + 360 - h));
			sd.add(new MsdfFontRender.StringDraw(size, Color.WHITE,
			                                     "a", 20 + pos, 70 + 360 - h, 1, 2F));
			pos += size*0.4F + 2;
		}
	}
	
	private void renderDecimatedCurve(Extent2D viewSize, CommandBuffer buf) throws VulkanCodeException{
		var t = (System.currentTimeMillis())/500D;
		
		var controlPoints = Iters.of(3D, 2D, 1D, 4D, 5D).enumerate((i, s) -> new Vector2f(
			(float)Math.sin(t/s)*100 + 200*(i + 1),
			(float)Math.cos(t/s)*100 + 200
		)).toList();
		
		lineRenderer.record(lineRes, Iters.concat1N(
			new Geometry.BezierCurve(controlPoints, 10, new Color(0.1F, 0.3F, 1, 0.6F), 30, 0.3),
			Iters.from(controlPoints)
			     .map(p -> new Geometry.PointsLine(List.of(p, p.add(0, 2, new Vector2f())), 2, Color.RED))
			     .toList()
		
		));
		
		var projectionMatrix2D = new Matrix3x2f()
			                         .translate(-1, -1)
			                         .scale(2F/viewSize.width, 2F/viewSize.height);
		lineRenderer.submit(viewSize, buf, projectionMatrix2D, lineRes);
	}
	
	private void renderAutoSizeByteGrid(Extent2D viewSize, CommandBuffer buf) throws VulkanCodeException{
		int byteCount = 32*32;
		
		var res = ByteGridSize.compute(viewSize, byteCount);
		byteGridRender.submit(viewSize, buf, new Matrix4f().scale(res.byteSize), res.bytesPerRow, grid1Res);
	}
	
	private record ByteGridSize(int bytesPerRow, float byteSize){
		private static ByteGridSize compute(Extent2D windowSize, int byteCount){
			float aspectRatio = windowSize.width/(float)windowSize.height;
			int   bytesPerRow = (int)Math.ceil(Math.sqrt(byteCount*aspectRatio));
			
			float byteSize = windowSize.width/(float)bytesPerRow;
			while(true){
				int   rows        = Math.ceilDiv(byteCount, bytesPerRow);
				float totalHeight = rows*byteSize;
				
				if(totalHeight<=windowSize.height){
					break;
				}
				bytesPerRow++;
				byteSize = windowSize.width/(float)bytesPerRow;
			}
			return new ByteGridSize(bytesPerRow, byteSize);
		}
	}
}
