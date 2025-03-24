package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DescriptorSetLayoutBinding;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.ShaderModule;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;

import java.util.List;

public class GraphicsPipeline implements VulkanResource{
	public final Device device;
	
	private Pipeline pipeline;
	
	public Descriptor.VkPool      descriptorPool;
	public Descriptor.VkLayout    descriptorSetLayout;
	public List<Descriptor.VkSet> descriptorSets;
	
	public GraphicsPipeline(Device device){
		this.device = device;
	}
	
	public void initDescriptor(
		int descriptorSetCount, List<DescriptorSetLayoutBinding> bindings, VkBuffer buffer, UniformBuffer uniformBuffers,
		VulkanTexture texture
	) throws VulkanCodeException{
		assert descriptorPool == null;
		
		descriptorPool = device.createDescriptorPool(descriptorSetCount, Flags.of());
		descriptorSetLayout = descriptorPool.createDescriptorSetLayout(bindings);
		descriptorSets = descriptorSetLayout.createDescriptorSets(descriptorSetCount);
		
		for(int i = 0; i<descriptorSets.size(); i++){
			Descriptor.VkSet descriptorSet = descriptorSets.get(i);
			descriptorSet.update(buffer, uniformBuffers.getBuffer(i), texture);
		}
	}
	
	public void initPipeline(RenderPass renderPass, int subpass, List<ShaderModule> modules,
	                         Rect2D viewport, Rect2D scissors,
	                         VkSampleCountFlag samples, Pipeline.Blending blending) throws VulkanCodeException{
		pipeline = device.createPipeline(
			renderPass, subpass, modules, viewport, scissors,
			VkPolygonMode.FILL, VkCullModeFlag.FRONT, VkFrontFace.CLOCKWISE, samples,
			descriptorSetLayout, blending
		);
	}
	
	@Override
	public void destroy(){
		descriptorSetLayout.destroy();
		descriptorPool.destroy();
		pipeline.destroy();
	}
	
	public Pipeline getPipeline(){
		return pipeline;
	}
}
