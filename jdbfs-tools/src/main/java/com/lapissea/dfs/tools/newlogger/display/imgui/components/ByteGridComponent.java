package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class ByteGridComponent implements UIComponent{
	
	private final ImBoolean open;
	
	public final MsdfFontRender fontRender;
	public final ByteGridRender byteGridRender;
	public final LineRenderer   lineRenderer;
	
	private final ByteGridRender.RenderResource grid1Res = new ByteGridRender.RenderResource();
	private final LineRenderer.RenderResource   lineRes  = new LineRenderer.RenderResource();
	
	public ByteGridComponent(ImBoolean open, MsdfFontRender fontRender, ByteGridRender byteGridRender, LineRenderer lineRenderer) throws VulkanCodeException{
		this.open = open;
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
		
		lineRenderer.record(lineRes, List.of(
			new Geometry.PointsLine(
				Iters.rangeMap(0, 50, u -> u/50F*Math.PI)
				     .map(f -> new Vector2f((float)Math.sin(f)*100 + 150, -(float)Math.cos(f)*100 + 150))
				     .toList(),
				5, Color.ORANGE
			)
		));
		
	}
	
	@Override
	public void imRender(TextureRegistry.Scope tScope){
		if(!open.get()) return;
		
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
		if(ImGui.begin("byteGrid", open)){
			ImGui.textColored(0xFF0000FF, "TODO");
		}
		ImGui.end();
		ImGui.popStyleVar();
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		grid1Res.destroy();
		lineRes.destroy();
	}
	
	private void recordToBuff(VulkanWindow window, CommandBuffer buf, int frameID) throws VulkanCodeException{
		
		renderAutoSizeByteGrid(window, frameID, buf);
		
		List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
		
		testFontWave(sd);
		
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
		fontRender.render(window, buf, frameID, sd);
		
		renderDecimatedCurve(window, buf);
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
	
	private void renderDecimatedCurve(VulkanWindow window, CommandBuffer buf) throws VulkanCodeException{
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
		
		lineRenderer.submit(window, buf, window.projectionMatrix2D, lineRes);
	}
	
	private void renderAutoSizeByteGrid(VulkanWindow window, int frameID, CommandBuffer buf) throws VulkanCodeException{
		int byteCount  = 32*32;
		var windowSize = window.swapchain.extent;
		
		var res = ByteGridSize.compute(windowSize, byteCount);
		byteGridRender.submit(window, buf, frameID, new Matrix4f().scale(res.byteSize), res.bytesPerRow, grid1Res);
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
