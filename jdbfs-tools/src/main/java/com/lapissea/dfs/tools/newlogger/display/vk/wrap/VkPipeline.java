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
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VkPipeline extends VulkanResource.DeviceHandleObj{
	
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
		return new Builder(renderPass, modules);
	}
	public static final class Builder{
		private final RenderPass                  renderPass;
		private       int                         subpass;
		private final List<ShaderModule>          modules;
		private       Rect2D                      viewport            = new Rect2D(0, 0, 100, 100);
		private       Rect2D                      scissors            = new Rect2D(0, 0, 100, 100);
		private       VkPolygonMode               polygonMode         = VkPolygonMode.FILL;
		private       VkCullModeFlag              cullMode            = VkCullModeFlag.FRONT;
		private       VkFrontFace                 frontFace           = VkFrontFace.CLOCKWISE;
		private       VkSampleCountFlag           sampleCount         = VkSampleCountFlag.N1;
		private       boolean                     multisampleShading;
		private final List<VkDescriptorSetLayout> desriptorSetLayouts = new ArrayList<>();
		private       VkPipeline.Blending         blending;
		private final Set<VkDynamicState>         dynamicStates       = new HashSet<>();
		
		private final Map<VkShaderStageFlag, Map<Integer, Object>> specializationValues = new EnumMap<>(VkShaderStageFlag.class);
		
		private Builder(RenderPass renderPass, List<ShaderModule> modules){
			this.renderPass = Objects.requireNonNull(renderPass);
			this.modules = Objects.requireNonNull(modules);
		}
		
		public RenderPass getRenderPass(){
			return renderPass;
		}
		
		public Builder subpass(int subpass){
			this.subpass = subpass;
			return this;
		}
		public Builder specializationValue(VkShaderStageFlag stage, int id, Object value){
			specializationValues.computeIfAbsent(stage, e -> new HashMap<>()).put(id, value);
			return this;
		}
		public Builder addDesriptorSetLayout(VkDescriptorSetLayout layout){
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
		public Builder blending(VkPipeline.Blending blending){
			this.blending = blending;
			return this;
		}
		public Builder dynamicState(VkDynamicState... dynamicStates){
			this.dynamicStates.addAll(Arrays.asList(dynamicStates));
			return this;
		}
		
		public VkPipeline build() throws VulkanCodeException{
			return renderPass.device.createPipeline(
				renderPass, subpass, modules, viewport, scissors,
				polygonMode, cullMode, frontFace,
				sampleCount, multisampleShading,
				desriptorSetLayouts, blending,
				dynamicStates, specializationValues
			);
		}
	}
	
	public final VkPipelineLayout layout;
	
	public VkPipeline(Device device, long handle, VkPipelineLayout layout){
		super(device, handle);
		this.layout = layout;
	}
	
	@Override
	public void destroy(){
		layout.destroy();
		VK10.vkDestroyPipeline(device.value, handle, null);
	}
}
