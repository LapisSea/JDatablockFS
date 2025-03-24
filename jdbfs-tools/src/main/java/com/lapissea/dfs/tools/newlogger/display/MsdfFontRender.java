package com.lapissea.dfs.tools.newlogger.display;

import com.google.gson.GsonBuilder;
import com.lapissea.dfs.tools.newlogger.display.vk.BufferAndMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.GraphicsPipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.ShaderModuleSet;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class MsdfFontRender implements VulkanResource{
	
	private static final String TEXTURE_PATH = "/roboto/light/atlas.png";
	private static final String LAYOUT_PATH  = "/roboto/light/atlas.json";
	
	private static class Vert{
		private static final int SIZE = (4 + 4)*4;
		
		private static void put(FloatBuffer dest,
		                        float x0, float y0, float x1, float y1,
		                        float u0, float v0, float u1, float v1){
			dest.put(x0).put(y0).put(x1).put(y1)
			    .put(u0).put(v0).put(u1).put(v1);
		}
	}
	
	record Metrics(double emSize, double lineHeight, double ascender, double descender, double underlineY, double underlineThickness){ }
	
	private static final class GlyphTable{
		
		private final int    distanceRange;
		private final double size;
		
		private final Metrics metrics;
		
		private final float[]   advance;
		private final boolean[] empty;
		private final float[]   x0, x1, y0, y1;
		private final float[] u0, u1, v0, v1;
		
		private final Map<Character, Integer> index;
		
		private int missingCharId;
		
		public GlyphTable(int distanceRange, double size, int width, int height, Metrics metrics, int count){
			this.metrics = metrics;
			index = HashMap.newHashMap(count);
			this.distanceRange = distanceRange;
			this.size = size;
			
			advance = new float[count];
			empty = new boolean[count];
			x0 = new float[count];
			x1 = new float[count];
			y0 = new float[count];
			y1 = new float[count];
			u0 = new float[count];
			u1 = new float[count];
			v0 = new float[count];
			v1 = new float[count];
		}
	}
	
	
	private static GlyphTable loadTable(){
		record Info(String type, int distanceRange, double size, int width, int height, String yOrigin){ }
		record Bounds(float left, float bottom, float right, float top){ }
		record Glyph(int unicode, float advance, Bounds planeBounds, Bounds atlasBounds){ }
		record AtlasInfo(Info atlas, Metrics metrics, List<Glyph> glyphs, List<?> kerning){ }
		
		AtlasInfo info;
		try(var jsonData = VUtils.class.getResourceAsStream(LAYOUT_PATH)){
			Objects.requireNonNull(jsonData);
			info = new GsonBuilder().create().fromJson(new InputStreamReader(jsonData, StandardCharsets.UTF_8), AtlasInfo.class);
		}catch(Throwable e){
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		var atlas  = info.atlas;
		var glyphs = info.glyphs;
		
		int width = atlas.width, height = atlas.height;
		
		var table = new GlyphTable(atlas.distanceRange, atlas.size, width, height, info.metrics, glyphs.size());
		
		for(int i = 0; i<glyphs.size(); i++){
			var glyph = glyphs.get(i);
			table.index.put((char)glyph.unicode, i);
			table.advance[i] = glyph.advance;
			
			var bounds   = glyph.planeBounds;
			var uvBounds = glyph.atlasBounds;
			
			var empty = bounds == null || uvBounds == null;
			table.empty[i] = empty;
			if(empty) continue;
			
			table.x0[i] = bounds.left;
			table.x1[i] = bounds.right;
			table.y0[i] = bounds.bottom;
			table.y1[i] = bounds.top;
			
			table.u0[i] = uvBounds.left/width;
			table.u1[i] = uvBounds.right/width;
			table.v0[i] = 1 - (uvBounds.top/height);
			table.v1[i] = 1 - (uvBounds.bottom/height);
		}
		
		table.missingCharId = Iters.of('\uFFFD', '?', ' ')
		                           .map(table.index::get)
		                           .findFirst()
		                           .orElseThrow();
		
		return table;
	}
	
	private VulkanCore core;
	
	private CompletableFuture<GlyphTable>    tableRes = CompletableFuture.supplyAsync(MsdfFontRender::loadTable);
	private CompletableFuture<VulkanTexture> atlas    = VulkanTexture.loadTexture(TEXTURE_PATH, true, () -> {
		UtilL.sleepWhile(() -> core == null);
		return core;
	});
	
	private final ShaderModuleSet shader = new ShaderModuleSet("msdfFont", ShaderType.VERTEX, ShaderType.FRAGMENT);
	
	public void init(VulkanCore core){
		this.core = Objects.requireNonNull(core);
		shader.init(core);
		
		atlas.whenComplete((glyphTable, e) -> {
			if(e != null) return;
			try{
				createResources();
			}catch(VulkanCodeException ex){
				throw new RuntimeException(ex);
			}
		});
	}
	
	private GraphicsPipeline pipeline;
	private UniformBuffer    uniform;
	private BufferAndMemory  verts;
	
	private void createResources() throws VulkanCodeException{
		verts = core.allocateCoherentBuffer(1000, VkBufferUsageFlag.STORAGE_BUFFER);
		uniform = core.allocateUniformBuffer((4*4*2 + 4 + 1)*4);
		
		var frag  = Iters.from(shader).filter(e -> e.stage == VkShaderStageFlag.FRAGMENT).getFirst();
		var table = tableRes.join();
		frag.specializationValues.put(0, (float)table.distanceRange);
		frag.specializationValues.put(1, (float)table.size);
		LogUtil.println("distanceRange", table.distanceRange, "size", table.size);
		createPipeline();
	}
	private void createPipeline() throws VulkanCodeException{
		pipeline = core.createPipeline(shader, verts.buffer, uniform, atlas.join(), Pipeline.Blending.STANDARD);
	}
	
	public void onResize() throws VulkanCodeException{
		waitFullyCreated();
		pipeline.destroy();
		createPipeline();
	}
	
	public void render(CommandBuffer buf, int frameID, String str) throws VulkanCodeException{
		waitFullyCreated();
		
		uniform.update(frameID, b -> {
			var f   = b.asFloatBuffer();
			var mat = new Matrix4f();
			mat.translate(301, 302, 0);
			mat.scale(100);
			mat.translate(-0.5F, -0.5F, 0);
			mat.get(f);
			
			core.screenOrthoMatrix.get(f.position(16));
			
			new Vector4f(1, 1, 1, 1).get(f.position(32));
			f.position(32 + 4).put(100);
		});
		
		var len = str.length();
		
		verts.update(0, (long)Vert.SIZE*len, b -> {
			var bb    = b.asFloatBuffer();
			var table = tableRes.join();
			
			var   metrics = table.metrics;
			float fsScale = (float)(1/(metrics.ascender - metrics.descender));
			
			float x = 0, y = (float)metrics.descender;
			
			for(int i = 0; i<len; i++){
				var ch = str.charAt(i);
				var p  = table.index.getOrDefault(ch, table.missingCharId);
				
				if(!table.empty[p]){
					
					Vert.put(bb,
					         (table.x0[p] + x)*fsScale, ((-table.y0[p]) + y)*fsScale,
					         (table.x1[p] + x)*fsScale, ((-table.y1[p]) + y)*fsScale,
					         table.u0[p], table.v0[p],
					         table.u1[p], table.v1[p]
					);
					
				}
				
				x += table.advance[p];
			}
		});
		
		buf.bindPipeline(pipeline, frameID);
		
		buf.draw(6*len, 1, 0, 0);
	}
	
	private void waitFullyCreated(){
		atlas.join();
		shader.size();
		UtilL.sleepWhile(() -> pipeline == null);
	}
	
	@Override
	public void destroy(){
		pipeline.destroy();
		uniform.destroy();
		verts.destroy();
		
		shader.destroy();
		atlas.join().destroy();
	}
}
