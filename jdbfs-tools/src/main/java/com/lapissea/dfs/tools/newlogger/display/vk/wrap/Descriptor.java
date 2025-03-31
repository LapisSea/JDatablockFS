package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.UniformBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

public final class Descriptor{
	
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
		
		public interface BindData extends VulkanResource{
			void write(VkWriteDescriptorSet.Buffer dest, int id);
		}
		
		private static final class UniformBuff implements BindData{
			
			private final VkDescriptorBufferInfo.Buffer infos;
			private final VkWriteDescriptorSet.Buffer   writes;
			
			private UniformBuff(int binding, UniformBuffer uniforms){
				infos = VkDescriptorBufferInfo.calloc(VulkanCore.MAX_IN_FLIGHT_FRAMES);
				for(int i = 0; i<infos.capacity(); i++){
					var buff = uniforms.getBuffer(i);
					var info = infos.get(i);
					info.buffer(buff.handle)
					    .offset(0)
					    .range(buff.size);
				}
				var type = uniforms.ssbo? VkDescriptorType.STORAGE_BUFFER : VkDescriptorType.UNIFORM_BUFFER;
				writes = VkWriteDescriptorSet.calloc(infos.capacity());
				for(int i = 0; i<infos.capacity(); i++){
					var write = writes.get(i);
					write.dstBinding(binding)
					     .descriptorType(type.id)
					     .pBufferInfo(infos.slice(i, 1));
				}
			}
			
			@Override
			public void write(VkWriteDescriptorSet.Buffer dest, int id){
				dest.put(writes.get(id));
			}
			
			@Override
			public void destroy(){
				writes.free();
				infos.free();
			}
		}
		
		private static final class TypeBuff implements BindData{
			
			private final VkDescriptorBufferInfo info;
			private final VkWriteDescriptorSet   write;
			
			private TypeBuff(int binding, VkDescriptorType type, VkBuffer buffer){
				info = VkDescriptorBufferInfo.calloc();
				info.buffer(buffer.handle)
				    .offset(0)
				    .range(buffer.size);
				write = VkWriteDescriptorSet.calloc();
				write.dstBinding(binding)
				     .descriptorType(type.id)
				     .pBufferInfo(VkDescriptorBufferInfo.create(info.address(), 1));
			}
			
			@Override
			public void write(VkWriteDescriptorSet.Buffer dest, int id){
				dest.put(write);
			}
			
			@Override
			public void destroy(){
				write.free();
				info.free();
			}
		}
		
		private static final class TextureBuff implements BindData{
			
			private final VkDescriptorImageInfo info;
			private final VkWriteDescriptorSet  write;
			
			private TextureBuff(int binding, VulkanTexture texture, VkImageLayout layout){
				info = VkDescriptorImageInfo.calloc();
				info.sampler(texture.sampler.handle)
				    .imageView(texture.view.handle)
				    .imageLayout(layout.id);
				write = VkWriteDescriptorSet.calloc();
				write.dstBinding(binding)
				     .descriptorType(VkDescriptorType.COMBINED_IMAGE_SAMPLER.id)
				     .pImageInfo(VkDescriptorImageInfo.create(info.address(), 1));
			}
			
			@Override
			public void write(VkWriteDescriptorSet.Buffer dest, int id){
				dest.put(write);
			}
			
			@Override
			public void destroy(){
				write.free();
				info.free();
			}
		}
		
		private final List<LayoutBinding> bindings = new ArrayList<>();
		
		private final SequencedMap<Integer, BindData> bindData = new LinkedHashMap<>();
		
		public LayoutDescription bind(int binding, VkShaderStageFlag stages, VulkanTexture texture, VkImageLayout layout){
			return bind(binding, Flags.of(stages), texture, layout);
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, VulkanTexture texture, VkImageLayout layout){
			bindings.add(new LayoutBinding(
				binding, VkDescriptorType.COMBINED_IMAGE_SAMPLER, 1, stages, List.of(texture.sampler)
			));
			bindData.put(binding, new TextureBuff(binding, texture, layout));
			return this;
		}
		public LayoutDescription bind(int binding, VkShaderStageFlag stages, UniformBuffer uniform){
			return bind(binding, Flags.of(stages), uniform);
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, UniformBuffer uniform){
			var type = uniform.ssbo? VkDescriptorType.STORAGE_BUFFER : VkDescriptorType.UNIFORM_BUFFER;
			bindings.add(new LayoutBinding(binding, type, stages));
			bindData.put(binding, new UniformBuff(binding, uniform));
			return this;
		}
		public LayoutDescription bind(int binding, VkShaderStageFlag stages, VkBuffer buffer, VkDescriptorType type){
			return bind(binding, Flags.of(stages), buffer, type);
		}
		public LayoutDescription bind(int binding, Flags<VkShaderStageFlag> stages, VkBuffer buffer, VkDescriptorType type){
			bindings.add(new LayoutBinding(binding, type, stages));
			bindData.put(binding, new TypeBuff(binding, type, buffer));
			return this;
		}
		
		public List<LayoutBinding> bindings(){
			return Collections.unmodifiableList(bindings);
		}
		public List<BindData> bindData(){
			return List.copyOf(bindData.values());
		}
	}
	
}
