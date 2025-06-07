package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentLoadOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentStoreOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent3D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkFence;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ByteGridComponent implements UIComponent{
	
	private static class RenderTarget{
		private final long          imageID;
		private final VulkanTexture image;
		private final VulkanTexture mssaImage;
		private final FrameBuffer   frameBuffer;
		private final VkFence       fence;
		private final CommandBuffer cmdBuffer;
		
		private final Instant               creationTime = Instant.now();
		private final TextureRegistry.Scope tScope;
		private       Extent2D              renderArea;
		
		RenderTarget(VulkanCore core, CommandPool cmdPool, TextureRegistry.Scope tScope, int width, int height) throws VulkanCodeException{
			this.tScope = tScope;
			image = createTexture(core, width, height, Flags.of(VkImageUsageFlag.COLOR_ATTACHMENT, VkImageUsageFlag.SAMPLED), VkSampleCountFlag.N1);
			mssaImage = createTexture(core, width, height, Flags.of(VkImageUsageFlag.TRANSIENT_ATTACHMENT, VkImageUsageFlag.COLOR_ATTACHMENT), core.physicalDevice.samples);
			
			try(var stack = MemoryStack.stackPush()){
				var viewRef = stack.mallocLong(2);
				var info = VkFramebufferCreateInfo.calloc(stack)
				                                  .sType$Default()
				                                  .renderPass(core.renderPass.handle)
				                                  .pAttachments(viewRef)
				                                  .width(width).height(height)
				                                  .layers(1);
				
				var mssaView      = mssaImage.view;
				var swapchainView = image.view;
				viewRef.clear().put(mssaView.handle).put(swapchainView.handle).flip();
				frameBuffer = VKCalls.vkCreateFramebuffer(core.device, info);
			}
			
			cmdBuffer = cmdPool.createCommandBuffer();
			fence = core.device.createFence(true);
			
			imageID = tScope.registerTextureAsID(image);
		}
		
		Extent3D size(){ return image.image.extent; }
		
		private VulkanTexture createTexture(VulkanCore core, int width, int height, Flags<VkImageUsageFlag> usage, VkSampleCountFlag samples) throws VulkanCodeException{
			var image = core.device.createImage(width, height, VkFormat.R8G8B8A8_UNORM,
			                                    usage,
			                                    samples, 1);
			var memory = image.allocateAndBindRequiredMemory(core.physicalDevice, VkMemoryPropertyFlag.DEVICE_LOCAL);
			var view   = image.createImageView(VkImageViewType.TYPE_2D, image.format, VkImageAspectFlag.COLOR);
			return new VulkanTexture(image, memory, view, core.defaultSampler, false);
		}
		
		private boolean isSizeOptimal(int width, int height){
			var extent = image.image.extent;
			if(extent.equals(width, height, 1)) return true;
			return extent.width>=width && extent.height>=height &&
			       extent.width<=width*1.2 && extent.height<=height*1.2;
		}
		
		public void destroy(){
			fence.destroy();
			cmdBuffer.destroy();
			tScope.releaseTexture(imageID);
			frameBuffer.destroy();
			image.destroy();
			mssaImage.destroy();
		}
	}
	
	private final ImBoolean  open;
	private final VulkanCore core;
	
	private final MsdfFontRender fontRender;
	private final ByteGridRender byteGridRender;
	private final LineRenderer   lineRenderer;
	
	private final ByteGridRender.RenderResource grid1Res = new ByteGridRender.RenderResource();
	private final LineRenderer.RenderResource   lineRes  = new LineRenderer.RenderResource();
	private final MsdfFontRender.RenderResource fontRes  = new MsdfFontRender.RenderResource();
	
	private final CommandPool cmdPool;
	private final RenderPass  renderPass;
	
	private RenderTarget renderTarget;
	
	public ByteGridComponent(
		ImBoolean open, VulkanCore core,
		MsdfFontRender fontRender, ByteGridRender byteGridRender, LineRenderer lineRenderer
	) throws VulkanCodeException{
		this.open = open;
		this.core = core;
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
		
		cmdPool = core.device.createCommandPool(core.renderQueueFamily, CommandPool.Type.NORMAL);
		renderPass = createRenderPass(core.device, VkFormat.R8G8B8A8_UNORM);
	}
	
	private RenderPass createRenderPass(Device device, VkFormat colorFormat) throws VulkanCodeException{
		var physicalDevice = device.physicalDevice;
		var mssaAttachment = new RenderPass.AttachmentInfo(
			colorFormat,
			physicalDevice.samples,
			VkAttachmentLoadOp.CLEAR, VkAttachmentStoreOp.STORE,
			VkImageLayout.UNDEFINED, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL
		);
		var presentAttachment = new RenderPass.AttachmentInfo(
			colorFormat,
			VkSampleCountFlag.N1,
			VkAttachmentLoadOp.DONT_CARE, VkAttachmentStoreOp.STORE,
			VkImageLayout.UNDEFINED, VkImageLayout.SHADER_READ_ONLY_OPTIMAL
		);
		
		var subpass = new RenderPass.SubpassInfo(
			VkPipelineBindPoint.GRAPHICS,
			List.of(),
			List.of(new RenderPass.AttachmentReference(0, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL)),
			List.of(new RenderPass.AttachmentReference(1, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL)),
			null,
			new int[0]
		);
		
		return device.buildRenderPass().attachment(mssaAttachment).attachment(presentAttachment).subpass(subpass).build();
	}
	
	@Override
	public void imRender(TextureRegistry.Scope tScope){
		if(!open.get()) return;
		
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
		if(ImGui.begin("byteGrid", open)){
			var width  = (int)ImGui.getContentRegionAvailX();
			var height = (int)ImGui.getContentRegionAvailY();
			drawFB(tScope, width, height);
			
			var renderArea = renderTarget.renderArea;
			var imageSize  = renderTarget.size();
			ImGui.image(renderTarget.imageID, width, height, 0, 0,
			            renderArea.width/(float)imageSize.width,
			            renderArea.height/(float)imageSize.height);
		}
		ImGui.end();
		ImGui.popStyleVar();
	}
	
	private void drawFB(TextureRegistry.Scope tScope, int width, int height){
		ensureImage(tScope, width, height);
		
		var fence     = renderTarget.fence;
		var cmdBuffer = renderTarget.cmdBuffer;
		
		try{
			fence.waitFor();
			fence.reset();
			
			cmdBuffer.reset();
			cmdBuffer.begin();
			
			var area = renderTarget.renderArea = new Extent2D(width, height);
			try(var ignore = cmdBuffer.beginRenderPass(renderPass, renderTarget.frameBuffer, area.asRect(), new Vector4f(0, 0, 0, 1))){
				recordToBuff(area, cmdBuffer);
			}
			cmdBuffer.end();
			
			core.renderQueue.submit(cmdBuffer, fence);
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to render", e);
		}
	}
	
	private void ensureImage(TextureRegistry.Scope tScope, int width, int height){
		if(renderTarget == null || !renderTarget.isSizeOptimal(width, height)){
			recreateImage(tScope, (int)(width*1.1), (int)(height*1.1));
		}else{
			var now          = Instant.now();
			var recentResize = Duration.between(renderTarget.creationTime, now).compareTo(Duration.ofMillis(1000))<0;
			if(!recentResize && !renderTarget.size().equals(width, height, 1)){
				recreateImage(tScope, width, height);
			}
		}
	}
	
	private void recreateImage(TextureRegistry.Scope tScope, int imgWidth, int imgHeight){
		core.device.waitIdle();
		if(renderTarget != null) renderTarget.destroy();
		try{
			renderTarget = new RenderTarget(core, cmdPool, tScope, imgWidth, imgHeight);
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to create images", e);
		}
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		if(renderTarget != null) renderTarget.destroy();
		grid1Res.destroy();
		lineRes.destroy();
		fontRes.destroy();
		
		cmdPool.destroy();
		renderPass.destroy();
	}
	
	private void recordToBuff(Extent2D viewSize, CommandBuffer buf) throws VulkanCodeException{
		
		renderAutoSizeByteGrid(viewSize, buf);
		
		List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
		
		testFontWave(sd);
		
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
		fontRender.render(viewSize, buf, fontRes, sd);
		
		renderDecimatedCurve(viewSize, buf);
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
