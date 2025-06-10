package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.util.function.UnsafeFunction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VkPipelineSet implements VulkanResource{
	
	private final Map<RenderPass, VkPipeline>                                 pipelines = new ConcurrentHashMap<>();
	private final UnsafeFunction<RenderPass, VkPipeline, VulkanCodeException> pipelineCreate;
	
	public VkPipelineSet(UnsafeFunction<RenderPass, VkPipeline, VulkanCodeException> pipelineCreate){
		this.pipelineCreate = pipelineCreate;
	}
	
	public VkPipeline get(RenderPass renderPass) throws VulkanCodeException{
		var pip = pipelines.get(renderPass);
		if(pip != null) return pip;
		return makePipeline(renderPass);
	}
	
	private synchronized VkPipeline makePipeline(RenderPass renderPass) throws VulkanCodeException{
		
		pipelines.entrySet().removeIf(e -> e.getKey().isDestroyed());
		
		var pip = pipelines.get(renderPass);
		if(pip != null) return pip;
		
		pip = pipelineCreate.apply(renderPass);
		pipelines.put(renderPass, pip);
		
		return pip;
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		pipelines.values().forEach(VkPipeline::destroy);
		pipelines.clear();
	}
}
