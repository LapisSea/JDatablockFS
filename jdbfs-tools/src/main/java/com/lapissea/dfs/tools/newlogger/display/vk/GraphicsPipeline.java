package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;

import java.util.List;

public final class GraphicsPipeline implements VulkanResource{
	public final Device device;
	
	private Pipeline pipeline;
	
	public VkDescriptorPool      descriptorPool;
	public VkDescriptorSetLayout descriptorSetLayout;
	public List<VkDescriptorSet> descriptorSets;
	
	public static GraphicsPipeline create(Descriptor.LayoutDescription description, Pipeline.Builder pipelineB) throws VulkanCodeException{
		var device = pipelineB.getRenderPass().device;
		var gp     = new GraphicsPipeline(device);
		
		gp.descriptorPool = device.createDescriptorPool(1000, Flags.of());
		gp.descriptorSetLayout = gp.descriptorPool.createDescriptorSetLayout(description.bindings());
		gp.descriptorSets = gp.descriptorSetLayout.createDescriptorSets(VulkanCore.MAX_IN_FLIGHT_FRAMES);
		
		gp.updateDescriptors(description);
		
		gp.pipeline = pipelineB.addDesriptorSetLayout(gp.descriptorSetLayout).build();
		return gp;
	}
	
	public void updateDescriptors(Descriptor.LayoutDescription description){
		for(int i = 0; i<descriptorSets.size(); i++){
			var descriptorSet = descriptorSets.get(i);
			descriptorSet.update(description.bindData(), i);
		}
	}
	
	private GraphicsPipeline(Device device){
		this.device = device;
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
