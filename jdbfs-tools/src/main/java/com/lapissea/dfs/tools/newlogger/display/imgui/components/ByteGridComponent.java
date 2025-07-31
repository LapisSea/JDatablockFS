package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender.StringDraw;
import com.lapissea.dfs.tools.newlogger.display.renderers.Renderer;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ByteGridComponent extends BackbufferComponent{
	
	private final MsdfFontRender fontRender;
	private final ByteGridRender byteGridRender;
	private final LineRenderer   lineRenderer;
	
	private       ByteGridRender.RenderResource grid1Res;
	private final Renderer.IndexedMeshBuffer    lineRes = new Renderer.IndexedMeshBuffer();
	private final MsdfFontRender.RenderResource fontRes = new MsdfFontRender.RenderResource();
	
	private record DisplayData(IOInterface src, long size){ }
	
	private DisplayData displayData;
	private boolean     uploadData;
	
	public ByteGridComponent(
		VulkanCore core, ImBoolean open, ImInt sampleEnumIndex,
		MsdfFontRender fontRender, LineRenderer lineRenderer
	) throws VulkanCodeException{
		super(core, open, sampleEnumIndex);
		
		this.fontRender = fontRender;
		this.lineRenderer = lineRenderer;
		
		byteGridRender = new ByteGridRender(core);
		
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
	protected void renderBackbuffer(DeviceGC deviceGC, CommandBuffer cmdBuffer, Extent2D viewSize) throws VulkanCodeException{
		
		fontRes.reset();
		lineRes.reset();
		
		{
			boolean errorMode = false;//TODO should error mode even be used? Bake whole database once instead of at render time?
			var     color     = errorMode? Color.RED.darker() : new Color(0xDBFFD700, true);
			
			var token = lineRenderer.record(deviceGC, lineRes, GridUtils.backgroundDots(viewSize, color));
			lineRenderer.submit(viewSize, cmdBuffer, viewMatrix(viewSize), token);
		}
		
		if(displayData.size == 0){
			if(grid1Res != null){
				deviceGC.destroyLater(grid1Res);
				grid1Res = null;
			}
			renderNoData(deviceGC, viewSize, cmdBuffer);
			return;
		}
		
		if(grid1Res == null){
			grid1Res = new ByteGridRender.RenderResource();
		}
		
		if(uploadData){
			uploadData = false;
			try{
				byteGridRender.record(
					deviceGC, grid1Res, displayData.src.readAll(),
					List.of(
						new ByteGridRender.DrawRange(0, MagicID.size(), Color.BLUE.darker()),
						new ByteGridRender.DrawRange(MagicID.size(), (int)displayData.size, Color.GRAY.brighter())
					),
					List.of()
				);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		long byteCount = displayData.size;
		
		var res      = GridUtils.ByteGridSize.compute(viewSize, byteCount);
		var byteSize = res.byteSize();
		
		var lines = GridUtils.outlineByteRange(Color.BLUE, res, new DrawUtils.Range(0, MagicID.size()), 3);
		var token = lineRenderer.record(deviceGC, lineRes, lines);
		
		byteGridRender.submit(viewSize, cmdBuffer, new Matrix4f().scale(byteSize), res.bytesPerRow(), grid1Res);
		lineRenderer.submit(viewSize, cmdBuffer, viewMatrix(viewSize), token);
		
		List<StringDraw> sd = new ArrayList<>();
		
		try{
			displayData.src.io(MagicID::read);
			
			var byteBae = new StringDraw(
				byteSize, new Color(0.1F, 0.3F, 1, 1), StandardCharsets.UTF_8.decode(MagicID.get()).toString(), 0, 0
			);
			
			if(stringDrawIn(byteBae, new GridUtils.Rect(0, 0, MagicID.size(), 1).scale(byteSize), false) instanceof Some(var str)){
				sd.add(str);
				sd.add(str.withOutline(Color.black, 1F));
			}
			
		}catch(IOException ignore){ }
		
		var fontDraws = fontRender.record(deviceGC, fontRes, sd);
		fontRender.submit(viewSize, cmdBuffer, List.of(fontDraws));
	}
	
	private void renderNoData(DeviceGC deviceGC, Extent2D viewSize, CommandBuffer cmdBuffer) throws VulkanCodeException{
		var str = "No data!";
		
		int w         = viewSize.width, h = viewSize.height;
		var fontScale = Math.min(h*0.8F, w/(str.length()*0.8F));
		
		List<MsdfFontRender.RenderToken> tokens = new ArrayList<>();
		
		if(stringDrawIn(str, new GridUtils.Rect(0, 0, w, h), Color.LIGHT_GRAY, fontScale, false) instanceof Some(var draw)){
			tokens.add(fontRender.record(deviceGC, fontRes, List.of(draw, draw.withOutline(new Color(0, 0, 0, 0.5F), 1.5F))));
		}
		
		fontRender.submit(viewSize, cmdBuffer, tokens);
	}
	
	private Match<StringDraw> stringDrawIn(StringDraw draw, GridUtils.Rect area, boolean alignLeft){
		return stringDrawIn(draw.string(), area, draw.color(), draw.pixelHeight(), alignLeft);
	}
	private Match<StringDraw> stringDrawIn(String s, GridUtils.Rect area, Color color, float fontScale, boolean alignLeft){
		return GridUtils.stringDrawIn(fontRender, s, area, color, fontScale, alignLeft);
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		super.unload(tScope);
		grid1Res.destroy();
		lineRes.destroy();
		fontRes.destroy();
		byteGridRender.destroy();
	}
	
	private static Matrix3x2f viewMatrix(Extent2D viewSize){
		return new Matrix3x2f()
			       .translate(-1, -1)
			       .scale(2F/viewSize.width, 2F/viewSize.height);
	}
	
}
