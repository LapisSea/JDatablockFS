package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.HUAWEISubpassShading.VK_PIPELINE_BIND_POINT_SUBPASS_SHADING_HUAWEI;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;

public enum VkPipelineBindPoint{
	
	GRAPHICS(VK_PIPELINE_BIND_POINT_GRAPHICS),
	COMPUTE(VK_PIPELINE_BIND_POINT_COMPUTE),
	RAY_TRACING_KHR(VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR),
	SUBPASS_SHADING_HUAWEI(VK_PIPELINE_BIND_POINT_SUBPASS_SHADING_HUAWEI),
	;
	
	public final int id;
	VkPipelineBindPoint(int id){ this.id = id; }
	
	private static final Map<Integer, VkPipelineBindPoint> BY_ID = Iters.from(VkPipelineBindPoint.class).toMap(e -> e.id, e -> e);
	
	public static VkPipelineBindPoint from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
	
}
