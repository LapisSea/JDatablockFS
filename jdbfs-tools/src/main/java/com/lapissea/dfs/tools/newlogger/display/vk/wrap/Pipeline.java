package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBlendFactor;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBlendOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Pipeline extends VulkanResource.DeviceHandleObj{
	
	public record Blending(
		VkBlendFactor srcColor, VkBlendFactor dstColor, VkBlendOp colorBlendOp,
		VkBlendFactor srcAlpha, VkBlendFactor dstAlpha, VkBlendOp alphaBlendOp
	){
		public static final Blending STANDARD = new Blending(
			VkBlendFactor.SRC_ALPHA, VkBlendFactor.ONE_MINUS_SRC_ALPHA, VkBlendOp.ADD
		);
		
		public Blending(VkBlendFactor src, VkBlendFactor dst, VkBlendOp blendOp){
			this(src, dst, blendOp, src, dst, blendOp);
		}
	}
	
	public static final class Builder{
		private       RenderPass                renderPass;
		private       int                       subpass;
		private       List<ShaderModule>        modules;
		private       Rect2D                    viewport;
		private       Rect2D                    scissors;
		private       VkPolygonMode             polygonMode         = VkPolygonMode.FILL;
		private       VkCullModeFlag            cullMode            = VkCullModeFlag.FRONT;
		private       VkFrontFace               frontFace           = VkFrontFace.CLOCKWISE;
		private       VkSampleCountFlag         sampleCount         = VkSampleCountFlag.N1;
		private       boolean                   multisampleShading;
		private final List<Descriptor.VkLayout> desriptorSetLayouts = new ArrayList<>();
		private       Pipeline.Blending         blending;
		
		
		public static Builder of(RenderPass renderPass, Rect2D viewportSize, List<ShaderModule> modules){
			var b = new Builder();
			b.renderPass = Objects.requireNonNull(renderPass);
			b.viewport = b.scissors = Objects.requireNonNull(viewportSize);
			b.modules = Objects.requireNonNull(modules);
			return b;
		}
		
		public RenderPass getRenderPass(){
			return renderPass;
		}
		
		public Builder subpass(int subpass){
			this.subpass = subpass;
			return this;
		}
		public Builder addDesriptorSetLayout(Descriptor.VkLayout layout){
			desriptorSetLayouts.add(layout);
			return this;
		}
		public Builder scissors(Rect2D scissors){
			this.scissors = scissors;
			return this;
		}
		public Builder polygonMode(VkPolygonMode polygonMode){
			this.polygonMode = polygonMode;
			return this;
		}
		public Builder cullMode(VkCullModeFlag cullMode){
			this.cullMode = cullMode;
			return this;
		}
		public Builder frontFace(VkFrontFace frontFace){
			this.frontFace = frontFace;
			return this;
		}
		public Builder multisampling(VkSampleCountFlag sampleCount, boolean multisampleShading){
			this.sampleCount = sampleCount;
			this.multisampleShading = multisampleShading;
			return this;
		}
		public Builder blending(Pipeline.Blending blending){
			this.blending = blending;
			return this;
		}
		
		public Pipeline build() throws VulkanCodeException{
			return renderPass.device.createPipeline(
				renderPass, subpass, modules, viewport, scissors,
				polygonMode, cullMode, frontFace,
				sampleCount, multisampleShading,
				desriptorSetLayouts, blending
			);
		}
	}
	
	
	public static class Layout extends VulkanResource.DeviceHandleObj{
		
		public Layout(Device device, long handle){ super(device, handle); }
		
		@Override
		public void destroy(){
			VK10.vkDestroyPipelineLayout(device.value, handle, null);
		}
	}
	
	public final Layout layout;
	
	public Pipeline(Device device, long handle, Layout layout){
		super(device, handle);
		this.layout = layout;
	}
	
	@Override
	public void destroy(){
		layout.destroy();
		VK10.vkDestroyPipeline(device.value, handle, null);
	}
}
