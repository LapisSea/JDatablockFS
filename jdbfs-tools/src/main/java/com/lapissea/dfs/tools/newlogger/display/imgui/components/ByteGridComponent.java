package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.tools.DrawFont;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.SimplexNoise;
import org.joml.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MsdfFontRender fontRender;
	private final ByteGridRender byteGridRender;
	private final LineRenderer   lineRenderer;
	
	private final ByteGridRender.RenderResource grid1Res = new ByteGridRender.RenderResource();
	private final LineRenderer.RenderResource   lineRes  = new LineRenderer.RenderResource();
	private final MsdfFontRender.RenderResource fontRes  = new MsdfFontRender.RenderResource();
	
	
	private record DisplayData(IOInterface src, long size){ }
	
	private DisplayData displayData;
	private boolean     uploadData;
	
	public ByteGridComponent(
		VulkanCore core, ImBoolean open, ImInt sampleEnumIndex,
		MsdfFontRender fontRender, ByteGridRender byteGridRender, LineRenderer lineRenderer
	) throws VulkanCodeException{
		super(core, open, sampleEnumIndex);
		
		this.fontRender = fontRender;
		this.byteGridRender = byteGridRender;
		this.lineRenderer = lineRenderer;
		
		displayData = new DisplayData(MemoryData.empty().asReadOnly(), 0);
		clearColor.set(0.4, 0.4, 0.4, 1);
	}
	
	public void setDisplayData(IOInterface src) throws IOException{
		assert src.isReadOnly();
		displayData = new DisplayData(src, src.getIOSize());
		uploadData = true;
	}
	
	@Override
	protected boolean needsRerender(){ return true; }
	
	@Override
	protected void renderBackbuffer(Extent2D viewSize, CommandBuffer cmdBuffer) throws VulkanCodeException{
		
		fontRes.reset();
		
		if(displayData.size == 0){
			renderNoData(viewSize, cmdBuffer);
			return;
		}
		
		if(uploadData){
			uploadData = false;
			try{
				byteGridRender.record(
					grid1Res, displayData.src.readAll(),
					List.of(new ByteGridRender.DrawRange(0, (int)displayData.size, Color.BLUE.brighter())),
					List.of()
				);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		renderAutoSizeByteGrid(viewSize, cmdBuffer);
		
		List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
		
		testFontWave(sd);
		
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
		
		var fontDraws = fontRender.record(fontRes, sd);
		fontRender.submit(viewSize, cmdBuffer, List.of(fontDraws));
		
		renderDecimatedCurve(viewSize, cmdBuffer);
	}
	
	private void renderNoData(Extent2D viewSize, CommandBuffer cmdBuffer) throws VulkanCodeException{
		lineRenderer.record(lineRes, backgroundDots(viewSize, false));
		lineRenderer.submit(viewSize, cmdBuffer, viewMatrix(viewSize), lineRes);
		
		var str = "No data!";
		
		int w         = viewSize.width, h = viewSize.height;
		var fontScale = Math.min(h*0.8F, w/(str.length()*0.8F));
		
		List<MsdfFontRender.RenderToken> tokens = new ArrayList<>();
		
		if(stringDrawIn(str, new Rect2D(0, 0, w, h), Color.LIGHT_GRAY, fontScale, false) instanceof Some(var draw)){
			tokens.add(fontRender.record(fontRes, List.of(draw, draw.withOutline(new Color(0, 0, 0, 0.5F), 1.5F))));
		}
		
		fontRender.submit(viewSize, cmdBuffer, tokens);
	}
	
	private List<Geometry.PointsLine> backgroundDots(Extent2D viewSize, boolean errorMode){
		
		var color = errorMode? Color.RED.darker() : new Color(0xDBFFD700, true);
		
		float jitter     = 125;
		int   step       = 25;
		float randX      = (float)ImGui.getTime()/10f;
		float randY      = randX + 10000;
		float noiseScale = 175;
		
		var res = new ArrayList<Geometry.PointsLine>();
		
		for(int x = 0; x<viewSize.width + 2; x += step){
			for(int y = (x/2)%step; y<viewSize.height + 2; y += step){
				float xf = x/noiseScale, yf = y/noiseScale;
				
				var nOff = new Vector2f(SimplexNoise.noise(xf, yf, randX), SimplexNoise.noise(xf, yf, randY));
				var pos  = new Vector2f(x, y).add(nOff.mul(jitter));
				res.add(new Geometry.PointsLine(List.of(
					pos, pos.add(2, 0, new Vector2f())
				), 2F, color, false));
			}
		}
		return res;
	}
	
	private Match<MsdfFontRender.StringDraw> stringDrawIn(String s, Rect2D area, Color color, float fontScale, boolean alignLeft){
		if(s.isEmpty()) return Match.empty();
		
		if(area.height<fontScale){
			fontScale = area.height;
		}
		
		float w, h;
		{
			var rect = fontRender.getStringBounds(s, fontScale);
			
			w = rect.width();
			h = rect.height();
			
			if(w>0){
				double scale = (area.width - 1)/w;
				if(scale<0.5){
					float div = scale<0.25? 3 : 2;
					fontScale /= div;
					w = rect.width()/div;
					h = rect.height()/div;
				}
				DrawFont.Bounds sbDots = null;
				while((area.width - 1)/w<0.5){
					if(s.isEmpty()) return Match.empty();
					if(s.length() == 1){
						break;
					}
					s = s.substring(0, s.length() - 1).trim();
					rect = fontRender.getStringBounds(s, fontScale);
					if(sbDots == null){
						sbDots = fontRender.getStringBounds("...", fontScale);
					}
					w = rect.width() + sbDots.width();
					h = Math.max(rect.height(), sbDots.height());
				}
			}
		}
		
		float x = area.x;
		float y = area.y;
		
		x += alignLeft? 0 : Math.max(0, area.width - w)/2F;
		y += h + (area.height - h)/2;
		
		float xScale = 1;
		if(w>0){
			double scale = (area.width - 1)/w;
			if(scale<1){
				xScale = (float)scale;
			}
		}
		
		return Match.of(new MsdfFontRender.StringDraw(fontScale, color, s, x, y, xScale, 0));
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
			     .map(p -> new Geometry.PointsLine(List.of(p, p.add(0, 2, new Vector2f())), 2, Color.RED, false))
			     .toList()
		
		));
		
		lineRenderer.submit(viewSize, buf, viewMatrix(viewSize), lineRes);
	}
	private static Matrix3x2f viewMatrix(Extent2D viewSize){
		return new Matrix3x2f()
			       .translate(-1, -1)
			       .scale(2F/viewSize.width, 2F/viewSize.height);
	}
	
	private void renderAutoSizeByteGrid(Extent2D viewSize, CommandBuffer buf) throws VulkanCodeException{
		long byteCount = displayData.size;
		
		var res = ByteGridSize.compute(viewSize, byteCount);
		byteGridRender.submit(viewSize, buf, new Matrix4f().scale(res.byteSize), res.bytesPerRow, grid1Res);
	}
	
	private record ByteGridSize(int bytesPerRow, float byteSize){
		private static ByteGridSize compute(Extent2D windowSize, long byteCount){
			if(byteCount == 0) return new ByteGridSize(1, 1);
			var byteCountL = BigDecimal.valueOf(byteCount);
			
			var aspectRatio = windowSize.width/(double)windowSize.height;
			int bytesPerRow = byteCountL.multiply(BigDecimal.valueOf(aspectRatio))
			                            .sqrt(MathContext.DECIMAL64).setScale(0, RoundingMode.UP)
			                            .intValue();
			
			float byteSize = windowSize.width/(float)bytesPerRow;
			while(true){
				int rows = byteCountL.divide(BigDecimal.valueOf(bytesPerRow), MathContext.DECIMAL32).setScale(0, RoundingMode.UP)
				                     .intValue();
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
