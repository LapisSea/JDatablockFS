package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;

import java.util.List;

public class GraphicsPipeline implements VulkanResource{
	public final Device device;
	
	private Pipeline pipeline;
	
	public Descriptor.VkPool      descriptorPool;
	public Descriptor.VkLayout    descriptorSetLayout;
	public List<Descriptor.VkSet> descriptorSets;
	
	public static GraphicsPipeline create(Descriptor.LayoutDescription description, Pipeline.Builder pipelineB) throws VulkanCodeException{
		var device             = pipelineB.getRenderPass().device;
		var gp                 = new GraphicsPipeline(device);
		var descriptorSetCount = VulkanCore.MAX_IN_FLIGHT_FRAMES;
		
		gp.descriptorPool = device.createDescriptorPool(descriptorSetCount, Flags.of());
		gp.descriptorSetLayout = gp.descriptorPool.createDescriptorSetLayout(description.bindings());
		gp.descriptorSets = gp.descriptorSetLayout.createDescriptorSets(descriptorSetCount);
		
		for(int i = 0; i<gp.descriptorSets.size(); i++){
			var descriptorSet = gp.descriptorSets.get(i);
			descriptorSet.update(description.bindData(), i);
		}
		
		gp.pipeline = pipelineB.addDesriptorSetLayout(gp.descriptorSetLayout).build();
		return gp;
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
