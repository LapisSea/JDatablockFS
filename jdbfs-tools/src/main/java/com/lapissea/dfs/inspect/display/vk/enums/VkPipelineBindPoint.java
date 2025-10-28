package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.HUAWEISubpassShading.VK_PIPELINE_BIND_POINT_SUBPASS_SHADING_HUAWEI;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;

public enum VkPipelineBindPoint implements VUtils.IDValue{
	
	GRAPHICS(VK_PIPELINE_BIND_POINT_GRAPHICS),
	COMPUTE(VK_PIPELINE_BIND_POINT_COMPUTE),
	RAY_TRACING_KHR(VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR),
	SUBPASS_SHADING_HUAWEI(VK_PIPELINE_BIND_POINT_SUBPASS_SHADING_HUAWEI),
	;
	
	public final int id;
	VkPipelineBindPoint(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkPipelineBindPoint from(int id){ return VUtils.fromID(VkPipelineBindPoint.class, id); }
}
