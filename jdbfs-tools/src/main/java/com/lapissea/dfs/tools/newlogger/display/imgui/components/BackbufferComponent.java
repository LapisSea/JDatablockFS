package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkFence;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImageView;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.joml.Vector4f;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public abstract class BackbufferComponent implements UIComponent{
	
	private static class RenderTarget{
		private final long          imageID;
		private final VulkanTexture image;
		private final VulkanTexture mssaImage;
		private final VkFence       fence;
		private final CommandBuffer cmdBuffer;
		
		private final FrameBuffer frameBuffer;
		private final RenderPass  renderPass;
		
		private final Instant               creationTime = Instant.now();
		private final TextureRegistry.Scope tScope;
		private       Extent2D              renderArea;
		
		RenderTarget(VulkanCore core, CommandPool cmdPool, TextureRegistry.Scope tScope, int width, int height, VkFormat format, VkSampleCountFlag samples) throws VulkanCodeException{
			this.tScope = tScope;
			image = createTexture(core, width, height, format, Flags.of(VkImageUsageFlag.COLOR_ATTACHMENT, VkImageUsageFlag.SAMPLED), VkSampleCountFlag.N1);
			mssaImage = samples == VkSampleCountFlag.N1? null :
			            createTexture(core, width, height, format, Flags.of(VkImageUsageFlag.TRANSIENT_ATTACHMENT, VkImageUsageFlag.COLOR_ATTACHMENT), samples);
			
			renderPass = core.getRenderPass(format, samples, VkImageLayout.SHADER_READ_ONLY_OPTIMAL, true);
			
			Map<RenderPass.AttachmentSlot, VkImageView> attachments = new HashMap<>();
			attachments.put(RenderPass.AttachmentSlot.RESULT_IMAGE, image.view);
			if(mssaImage != null) attachments.put(RenderPass.AttachmentSlot.MULTISAMPLE_INTERMEDIATE_IMAGE, mssaImage.view);
			frameBuffer = renderPass.createFrameBuffer(attachments, size());
			
			cmdBuffer = cmdPool.createCommandBuffer();
			fence = core.device.createFence(true);
			
			imageID = tScope.registerTextureAsID(image);
		}
		
		VkSampleCountFlag getSamples(){
			return mssaImage == null? VkSampleCountFlag.N1 : mssaImage.image.samples;
		}
		
		CommandBuffer.RenderPassScope beginRenderPass(CommandBuffer buff, Vector4f clearColor){
			return buff.beginRenderPass(renderPass, frameBuffer, renderArea.asRect(), clearColor);
		}
		
		private Extent2D size(){ return image.image.extent.to2D(); }
		
		private static VulkanTexture createTexture(VulkanCore core, int width, int height, VkFormat format, Flags<VkImageUsageFlag> usage, VkSampleCountFlag samples) throws VulkanCodeException{
			var image  = core.device.createImage(width, height, format, usage, samples, 1);
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
			if(mssaImage != null) mssaImage.destroy();
		}
	}
	
	private final CommandPool cmdPool;
	
	private RenderTarget renderTarget;
	
	private final VulkanCore core;
	private final ImBoolean  open;
	private final ImInt      sampleEnumIndex;
	
	public BackbufferComponent(VulkanCore core, ImBoolean open, ImInt sampleEnumIndex) throws VulkanCodeException{
		this.core = core;
		this.open = open;
		cmdPool = core.device.createCommandPool(core.renderQueueFamily, CommandPool.Type.NORMAL);
		this.sampleEnumIndex = sampleEnumIndex;
	}
	
	protected abstract boolean needsRerender();
	protected abstract void renderBackbuffer(Extent2D viewSize, CommandBuffer cmdBuffer) throws VulkanCodeException;
	
	@Override
	public void imRender(TextureRegistry.Scope tScope){
		if(!open.get()) return;
		
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
		if(ImGui.begin("byteGrid", open)){
			var width  = (int)ImGui.getContentRegionAvailX();
			var height = (int)ImGui.getContentRegionAvailY();
			
			if(renderTarget != null && sampleEnumIndex.get() != renderTarget.getSamples().ordinal()){
				renderTarget.fence.device.waitIdle();
				renderTarget.destroy();
				renderTarget = null;
			}
			
			if(renderTarget == null || !renderTarget.renderArea.equals(width, height) || needsRerender()){
				drawFB(tScope, width, height);
			}
			
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
		
		try{
			renderTarget.fence.waitReset();
			
			var buff = renderTarget.cmdBuffer;
			buff.reset();
			buff.begin();
			
			renderTarget.renderArea = new Extent2D(width, height);
			try(var ignore = renderTarget.beginRenderPass(buff, new Vector4f(0, 0, 0, 1))){
				renderBackbuffer(renderTarget.renderArea, buff);
			}
			buff.end();
			
			core.renderQueue.submit(buff, renderTarget.fence);
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
			if(!recentResize && !renderTarget.size().equals(width, height)){
				recreateImage(tScope, width, height);
			}
		}
	}
	
	private void recreateImage(TextureRegistry.Scope tScope, int imgWidth, int imgHeight){
		core.device.waitIdle();
		if(renderTarget != null) renderTarget.destroy();
		try{
			var samples = VkSampleCountFlag.values()[sampleEnumIndex.get()];
			renderTarget = new RenderTarget(core, cmdPool, tScope, imgWidth, imgHeight, VkFormat.R8G8B8A8_UNORM, samples);
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to create images", e);
		}
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope) throws VulkanCodeException{
		if(renderTarget != null) renderTarget.destroy();
		cmdPool.destroy();
	}
}
