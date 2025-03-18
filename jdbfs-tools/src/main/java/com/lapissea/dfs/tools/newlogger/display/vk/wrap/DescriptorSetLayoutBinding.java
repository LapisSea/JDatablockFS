package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;

public class DescriptorSetLayoutBinding{
	
	public final int                      binding;
	public final VkDescriptorType         descriptorType;
	public final int                      descriptorCount;
	public final Flags<VkShaderStageFlag> stageFlags;
	
	public DescriptorSetLayoutBinding(int binding, VkDescriptorType descriptorType, int descriptorCount, Flags<VkShaderStageFlag> stageFlags){
		this.binding = binding;
		this.descriptorType = descriptorType;
		this.descriptorCount = descriptorCount;
		this.stageFlags = stageFlags;
	}
	
	public void set(VkDescriptorSetLayoutBinding.Buffer dst){
		dst.binding(binding)
		   .descriptorType(descriptorType.id)
		   .descriptorCount(descriptorCount)
		   .stageFlags(stageFlags.value)
		   .pImmutableSamplers(null);
	}
}
