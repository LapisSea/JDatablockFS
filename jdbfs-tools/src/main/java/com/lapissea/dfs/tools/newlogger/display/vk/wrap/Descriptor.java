package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Descriptor{
	
	public static final class VkPool extends VulkanResource.DeviceHandleObj{
		
		public VkPool(Device device, long handle){ super(device, handle); }
		
		public VkLayout createDescriptorSetLayout(List<LayoutBinding> bindings) throws VulkanCodeException{
			
			try(var stack = MemoryStack.stackPush()){
				var descriptorBindings = VkDescriptorSetLayoutBinding.malloc(bindings.size(), stack);
				for(int i = 0; i<bindings.size(); i++){
					var binding = bindings.get(i);
					binding.set(descriptorBindings.position(i), stack);
				}
				descriptorBindings.position(0);
				
				var pCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				                                                 .sType$Default()
				                                                 .pBindings(descriptorBindings);
				
				return VKCalls.vkCreateDescriptorSetLayout(this, pCreateInfo);
			}
			
		}
		
		@Override
		public void destroy(){
			VK10.vkDestroyDescriptorPool(device.value, handle, null);
		}
	}
	
	
	public static class VkLayout extends VulkanResource.DeviceHandleObj{
		
		public final VkPool pool;
		
		public VkLayout(VkPool pool, long handle){
			super(pool.device, handle);
			this.pool = pool;
		}
		
		public List<VkSet> createDescriptorSets(int count) throws VulkanCodeException{
			try(var stack = MemoryStack.stackPush()){
				var layouts = stack.mallocLong(count);
				for(int i = 0; i<count; i++){
					layouts.put(i, handle);
				}
				var pAllocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
				                                               .sType$Default()
				                                               .descriptorPool(pool.handle)
				                                               .pSetLayouts(layouts);
				
				return VKCalls.vkAllocateDescriptorSets(device, pAllocateInfo);
			}
		}
		
		@Override
		public void destroy(){
			VK10.vkDestroyDescriptorSetLayout(device.value, handle, null);
		}
	}
	
	
	public static class VkSet extends VulkanResource.DeviceHandleObj{
		
		public VkSet(Device device, long handle){ super(device, handle); }
		
		public void update(List<LayoutDescription.BindData> bindings, int id){
			
			try(MemoryStack stack = MemoryStack.stackPush()){
				
				var info = VkWriteDescriptorSet.calloc(bindings.size(), stack);
				
				for(int i = 0; i<info.capacity(); i++){
					info.position(i)
					    .sType$Default()
					    .dstSet(handle)
					    .dstArrayElement(0)
					    .descriptorCount(1);
				}
				info.position(0);
				
				for(int i = 0; i<bindings.size(); i++){
					var binding = bindings.get(i);
					binding.write(info.get(i), stack, id);
				}
				
				VK10.vkUpdateDescriptorSets(device.value, info, null);
			}
			
		}
		
		@Override
		public void destroy(){ }
	}
	
	public static class LayoutBinding{
		
		public final int                      binding;
		public final VkDescriptorType         descriptorType;
		public final int                      descriptorCount;
		public final Flags<VkShaderStageFlag> stageFlags;
		public final List<VkSampler>          immutableSamplers;
		
		public LayoutBinding(int binding, VkDescriptorType descriptorType, VkShaderStageFlag stageFlags){
			this(binding, descriptorType, Flags.of(stageFlags));
		}
		public LayoutBinding(int binding, VkDescriptorType descriptorType, Flags<VkShaderStageFlag> stageFlags){
			this(binding, descriptorType, 1, stageFlags, List.of());
		}
		public LayoutBinding(
			int binding, VkDescriptorType descriptorType, int descriptorCount,
			Flags<VkShaderStageFlag> stageFlags, List<VkSampler> immutableSamplers
		){
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
	
	public static final class LayoutDescription{
		
		public interface BindData{
			void write(VkWriteDescriptorSet dest, MemoryStack stack, int id);
		}
		
		private record UniformBuff(int binding, UniformBuffer uniforms) implements BindData{
			@Override
			public void write(VkWriteDescriptorSet dest, MemoryStack stack, int id){
				new VertBuff(binding, VkDescriptorType.UNIFORM_BUFFER, uniforms.getBuffer(id)).write(dest, stack, id);
			}
		}
		
		private record VertBuff(int binding, VkDescriptorType type, VkBuffer buffer) implements BindData{
			@Override
			public void write(VkWriteDescriptorSet dest, MemoryStack stack, int id){
				dest.dstBinding(binding)
				    .descriptorType(type.id)
				    .pBufferInfo(VkDescriptorBufferInfo.malloc(1, stack)
				                                       .buffer(buffer.handle)
				                                       .offset(0)
				                                       .range(buffer.size));
			}
		}
		
		private record TextureBuff(int binding, VulkanTexture texture, VkImageLayout layout) implements BindData{
			@Override
			public void write(VkWriteDescriptorSet dest, MemoryStack stack, int id){
				dest.dstBinding(binding)
				    .descriptorType(VkDescriptorType.COMBINED_IMAGE_SAMPLER.id)
				    .pImageInfo(VkDescriptorImageInfo.malloc(1, stack)
				                                     .sampler(texture.sampler.handle)
				                                     .imageView(texture.view.handle)
				                                     .imageLayout(layout.id));
			}
		}
		
		private final List<LayoutBinding> bindings = new ArrayList<>();
		
		private final List<BindData> bindData = new ArrayList<>();
		
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, VulkanTexture texture, VkImageLayout layout){
			bindings.add(new LayoutBinding(
				binding, VkDescriptorType.COMBINED_IMAGE_SAMPLER, 1, stages, List.of(texture.sampler)
			));
			bindData.add(new TextureBuff(binding, texture, layout));
			return this;
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, UniformBuffer buffer){
			bindings.add(new LayoutBinding(binding, VkDescriptorType.UNIFORM_BUFFER, stages));
			bindData.add(new UniformBuff(binding, buffer));
			return this;
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, VkBuffer buffer, VkDescriptorType type){
			bindings.add(new LayoutBinding(binding, type, stages));
			bindData.add(new VertBuff(binding, type, buffer));
			return this;
		}
		
		public List<LayoutBinding> bindings(){
			return Collections.unmodifiableList(bindings);
		}
		public List<BindData> bindData(){
			return Collections.unmodifiableList(bindData);
		}
	}
	
}
