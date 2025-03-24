package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.VK14.VK_DYNAMIC_STATE_LINE_STIPPLE;

public enum VkDynamicState implements VUtils.IDValue{
	VIEWPORT(VK_DYNAMIC_STATE_VIEWPORT),
	SCISSOR(VK_DYNAMIC_STATE_SCISSOR),
	LINE_WIDTH(VK_DYNAMIC_STATE_LINE_WIDTH),
	DEPTH_BIAS(VK_DYNAMIC_STATE_DEPTH_BIAS),
	BLEND_CONSTANTS(VK_DYNAMIC_STATE_BLEND_CONSTANTS),
	DEPTH_BOUNDS(VK_DYNAMIC_STATE_DEPTH_BOUNDS),
	STENCIL_COMPARE_MASK(VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK),
	STENCIL_WRITE_MASK(VK_DYNAMIC_STATE_STENCIL_WRITE_MASK),
	STENCIL_REFERENCE(VK_DYNAMIC_STATE_STENCIL_REFERENCE),
	CULL_MODE(VK_DYNAMIC_STATE_CULL_MODE),
	FRONT_FACE(VK_DYNAMIC_STATE_FRONT_FACE),
	PRIMITIVE_TOPOLOGY(VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY),
	VIEWPORT_WITH_COUNT(VK_DYNAMIC_STATE_VIEWPORT_WITH_COUNT),
	SCISSOR_WITH_COUNT(VK_DYNAMIC_STATE_SCISSOR_WITH_COUNT),
	VERTEX_INPUT_BINDING_STRIDE(VK_DYNAMIC_STATE_VERTEX_INPUT_BINDING_STRIDE),
	DEPTH_TEST_ENABLE(VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE),
	DEPTH_WRITE_ENABLE(VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE),
	DEPTH_COMPARE_OP(VK_DYNAMIC_STATE_DEPTH_COMPARE_OP),
	DEPTH_BOUNDS_TEST_ENABLE(VK_DYNAMIC_STATE_DEPTH_BOUNDS_TEST_ENABLE),
	STENCIL_TEST_ENABLE(VK_DYNAMIC_STATE_STENCIL_TEST_ENABLE),
	STENCIL_OP(VK_DYNAMIC_STATE_STENCIL_OP),
	RASTERIZER_DISCARD_ENABLE(VK_DYNAMIC_STATE_RASTERIZER_DISCARD_ENABLE),
	DEPTH_BIAS_ENABLE(VK_DYNAMIC_STATE_DEPTH_BIAS_ENABLE),
	PRIMITIVE_RESTART_ENABLE(VK_DYNAMIC_STATE_PRIMITIVE_RESTART_ENABLE),
	LINE_STIPPLE(VK_DYNAMIC_STATE_LINE_STIPPLE),
	;
	
	public final int id;
	VkDynamicState(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkDynamicState from(int id){ return VUtils.fromID(VkDynamicState.class, id); }
}
