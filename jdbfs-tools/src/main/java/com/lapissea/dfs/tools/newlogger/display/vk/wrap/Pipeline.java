package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBlendFactor;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBlendOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDynamicState;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
	
	public static Builder builder(RenderPass renderPass, List<ShaderModule> modules){
		return Builder.of(renderPass, modules);
	}
	public static final class Builder{
		private       RenderPass                renderPass;
		private       int                       subpass;
		private       List<ShaderModule>        modules;
		private       Rect2D                    viewport            = new Rect2D(0, 0, 100, 100);
		private       Rect2D                    scissors            = new Rect2D(0, 0, 100, 100);
		private       VkPolygonMode             polygonMode         = VkPolygonMode.FILL;
		private       VkCullModeFlag            cullMode            = VkCullModeFlag.FRONT;
		private       VkFrontFace               frontFace           = VkFrontFace.CLOCKWISE;
		private       VkSampleCountFlag         sampleCount         = VkSampleCountFlag.N1;
		private       boolean                   multisampleShading;
		private final List<Descriptor.VkLayout> desriptorSetLayouts = new ArrayList<>();
		private       Pipeline.Blending         blending;
		private       Set<VkDynamicState>       dynamicStates       = new HashSet<>();
		
		private Builder(){ }
		
		public static Builder of(RenderPass renderPass, List<ShaderModule> modules){
			var b = new Builder();
			b.renderPass = Objects.requireNonNull(renderPass);
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
		public Builder viewport(Rect2D viewport){
			this.viewport = viewport;
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
		public Builder dynamicState(VkDynamicState... dynamicStates){
			this.dynamicStates.addAll(Arrays.asList(dynamicStates));
			return this;
		}
		
		public Pipeline build() throws VulkanCodeException{
			return renderPass.device.createPipeline(
				renderPass, subpass, modules, viewport, scissors,
				polygonMode, cullMode, frontFace,
				sampleCount, multisampleShading,
				desriptorSetLayouts, blending,
				dynamicStates
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
