package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK12.VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE;

public enum VkSamplerAddressMode implements VUtils.IDValue{
	REPEAT(VK_SAMPLER_ADDRESS_MODE_REPEAT),
	MIRRORED_REPEAT(VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT),
	CLAMP_TO_EDGE(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
	CLAMP_TO_BORDER(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER),
	MIRROR_CLAMP_TO_EDGE(VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE),
	;
	
	public final int id;
	VkSamplerAddressMode(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkSamplerAddressMode from(int id){ return VUtils.fromID(VkSamplerAddressMode.class, id); }
}
