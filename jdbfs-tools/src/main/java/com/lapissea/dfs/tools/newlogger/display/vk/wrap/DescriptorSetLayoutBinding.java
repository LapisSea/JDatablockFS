package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;

import java.nio.LongBuffer;
import java.util.List;

public class DescriptorSetLayoutBinding{
	
	public final int                      binding;
	public final VkDescriptorType         descriptorType;
	public final int                      descriptorCount;
	public final Flags<VkShaderStageFlag> stageFlags;
	public final List<VkSampler>          immutableSamplers;
	
	public DescriptorSetLayoutBinding(int binding, VkDescriptorType descriptorType, VkShaderStageFlag stageFlags){
		this(binding, descriptorType, Flags.of(stageFlags));
	}
	public DescriptorSetLayoutBinding(int binding, VkDescriptorType descriptorType, Flags<VkShaderStageFlag> stageFlags){
		this(binding, descriptorType, 1, stageFlags, List.of());
	}
	public DescriptorSetLayoutBinding(int binding, VkDescriptorType descriptorType, int descriptorCount, Flags<VkShaderStageFlag> stageFlags, List<VkSampler> immutableSamplers){
		this.binding = binding;
		this.descriptorType = descriptorType;
		this.descriptorCount = descriptorCount;
		this.stageFlags = stageFlags;
		this.immutableSamplers = immutableSamplers;
	}
	
	public void set(VkDescriptorSetLayoutBinding.Buffer dst, MemoryStack stack){
		
		LongBuffer samplers = null;
		if(!immutableSamplers.isEmpty()){
			samplers = stack.mallocLong(immutableSamplers.size());
			for(var s : immutableSamplers){
				samplers.put(s.handle);
			}
			samplers.flip();
		}
		
		dst.binding(binding)
		   .descriptorType(descriptorType.id)
		   .descriptorCount(descriptorCount)
		   .stageFlags(stageFlags.value)
		   .pImmutableSamplers(samplers);
	}
}
