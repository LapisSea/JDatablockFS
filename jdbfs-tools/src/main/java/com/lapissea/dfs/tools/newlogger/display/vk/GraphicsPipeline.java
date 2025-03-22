package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DescriptorPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DescriptorSetLayout;
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
	
	public DescriptorPool      descriptorPool;
	public DescriptorSetLayout descriptorSetLayout;
	public List<DescriptorSet> descriptorSets;
	
	public GraphicsPipeline(Device device){
		this.device = device;
	}
	
	public void initDescriptor(
		int descriptorSetCount, List<DescriptorSetLayoutBinding> bindings, VkBuffer buffer, List<BufferAndMemory> uniformBuffers,
		VulkanTexture texture
	) throws VulkanCodeException{
		assert descriptorPool == null;
		
		descriptorPool = device.createDescriptorPool(descriptorSetCount, Flags.of());
		descriptorSetLayout = descriptorPool.createDescriptorSetLayout(bindings);
		descriptorSets = descriptorSetLayout.createDescriptorSets(descriptorSetCount);
		
		for(int i = 0; i<descriptorSets.size(); i++){
			DescriptorSet descriptorSet = descriptorSets.get(i);
			descriptorSet.update(buffer, uniformBuffers.get(i).buffer, texture);
		}
	}
	
	public void initPipeline(RenderPass renderPass, int subpass, List<ShaderModule> modules, Rect2D viewport, Rect2D scissors) throws VulkanCodeException{
		pipeline = device.createPipeline(
			renderPass, subpass, modules, viewport, scissors,
			VkPolygonMode.FILL, VkCullModeFlag.FRONT, VkFrontFace.CLOCKWISE, VkSampleCountFlag.N1,
			descriptorSetLayout
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
